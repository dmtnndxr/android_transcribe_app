package dev.notune.transcribe;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * On-device history of dictations: the recording, what was recognized, what
 * AI cleanup or a voice edit made of it, and where the text ended up.
 *
 * <p>Written by the voice keyboard (its own {@code :ime} process) and the
 * speech-recognition service, read by {@link HistoryActivity}. SQLite handles
 * the cross-process access. Writes go through a single background thread so
 * the keyboard never waits on disk; callers get the entry id up front.
 *
 * <p>Nothing here leaves the device: the database and recordings live in
 * app-private internal storage, which the media scanner and other apps
 * (players, galleries) can't see, and are excluded from cloud backup
 * (res/xml/backup_rules.xml, res/xml/data_extraction_rules.xml).
 */
public final class HistoryStore {

    private static final String TAG = "HistoryStore";

    // What produced the entry.
    public static final String SOURCE_DICTATION = "dictation";
    public static final String SOURCE_AI = "ai";
    public static final String SOURCE_EDIT = "edit";
    public static final String SOURCE_OTHER_KEYBOARD = "other_keyboard";

    // Where the text ended up.
    public static final String OUTCOME_PENDING = "pending";
    public static final String OUTCOME_INSERTED = "inserted";
    public static final String OUTCOME_CLIPBOARD = "clipboard";
    public static final String OUTCOME_KEPT = "kept";
    public static final String OUTCOME_UNCHANGED = "unchanged";
    public static final String OUTCOME_FAILED = "failed";
    public static final String OUTCOME_CANCELED = "canceled";

    // Settings, as marker/config files like the rest of the app's prefs.
    private static final String HISTORY_OFF = "history_off";
    private static final String AUDIO_OFF = "history_audio_off";
    private static final String AUDIO_DAYS = "history_audio_days";
    /** Recordings are kept until the user deletes them unless they pick a limit. */
    public static final int KEEP_FOREVER = 0;
    public static final int DEFAULT_AUDIO_DAYS = KEEP_FOREVER;

    private static final String DB_NAME = "history.db";
    private static final String TABLE = "entries";
    private static final String AUDIO_DIR = "history";

    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor();
    private static Helper helper;

    private HistoryStore() {}

    public static final class Entry {
        public String id;
        public long created;
        public long durationMs;
        public String source;
        /** What the speech model recognized (for an edit: the spoken instruction). */
        public String rawText;
        /** What was finally produced: AI output, the edit's replacement, or rawText. */
        public String finalText;
        /** For an edit: the selected text before it was replaced. */
        public String originalText;
        public String outcome;
        /** Why AI processing failed, if it did. */
        public String note;
        /** Recording file name in the history folder, or null. */
        public String audio;
    }

    // --- Settings ------------------------------------------------------------

    public static boolean isEnabled(Context ctx) {
        return !new File(ctx.getFilesDir(), HISTORY_OFF).exists();
    }

    public static void setEnabled(Context ctx, boolean enabled) {
        setMarker(ctx, HISTORY_OFF, !enabled);
    }

    public static boolean isAudioEnabled(Context ctx) {
        return isEnabled(ctx) && isAudioSettingOn(ctx);
    }

    /** The "keep recordings" switch itself, regardless of the history switch. */
    public static boolean isAudioSettingOn(Context ctx) {
        return !new File(ctx.getFilesDir(), AUDIO_OFF).exists();
    }

    public static void setAudioEnabled(Context ctx, boolean enabled) {
        setMarker(ctx, AUDIO_OFF, !enabled);
    }

    public static int getAudioDays(Context ctx) {
        File f = new File(ctx.getFilesDir(), AUDIO_DAYS);
        if (!f.exists()) return DEFAULT_AUDIO_DAYS;
        try {
            return Integer.parseInt(new String(Files.readAllBytes(f.toPath()),
                    StandardCharsets.UTF_8).trim());
        } catch (IOException | NumberFormatException e) {
            return DEFAULT_AUDIO_DAYS;
        }
    }

    public static void setAudioDays(Context ctx, int days) {
        try {
            Files.write(new File(ctx.getFilesDir(), AUDIO_DAYS).toPath(),
                    String.valueOf(days).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.e(TAG, "Failed to save audio retention", e);
        }
    }

    private static void setMarker(Context ctx, String name, boolean present) {
        File marker = new File(ctx.getFilesDir(), name);
        if (present) {
            try {
                marker.createNewFile();
            } catch (IOException e) {
                Log.e(TAG, "Failed to create " + name, e);
            }
        } else {
            marker.delete();
        }
    }

    // --- Recordings ----------------------------------------------------------

    public static File audioDir(Context ctx) {
        File dir = new File(ctx.getFilesDir(), AUDIO_DIR);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /** A fresh path for the next recording, or null if audio isn't kept. */
    public static File newAudioFile(Context ctx) {
        if (!isAudioEnabled(ctx)) return null;
        return new File(audioDir(ctx), UUID.randomUUID() + ".wav");
    }

    public static File audioFile(Context ctx, Entry e) {
        return e.audio == null ? null : new File(audioDir(ctx), e.audio);
    }

    // --- Writing (asynchronous) ----------------------------------------------

    /**
     * Records a new transcription and returns its id for later updates, or
     * null if history is off (the recording, if any, is deleted then).
     */
    public static String add(Context ctx, String source, String rawText,
                             File audio, long durationMs) {
        if (!isEnabled(ctx)) {
            if (audio != null) audio.delete();
            return null;
        }
        final Context app = ctx.getApplicationContext();
        final String id = UUID.randomUUID().toString();
        final ContentValues v = new ContentValues();
        v.put("id", id);
        v.put("created", System.currentTimeMillis());
        v.put("duration_ms", durationMs);
        v.put("source", source);
        v.put("raw_text", rawText);
        v.put("final_text", rawText);
        v.put("outcome", OUTCOME_PENDING);
        if (audio != null && audio.exists()) v.put("audio", audio.getName());
        WRITER.execute(() -> {
            try {
                db(app).insert(TABLE, null, v);
                prune(app);
            } catch (RuntimeException e) {
                Log.e(TAG, "Failed to add history entry", e);
            }
        });
        return id;
    }

    /** Fills in the result once the text has gone somewhere. */
    public static void finish(Context ctx, String id, String finalText,
                              String originalText, String outcome, String note) {
        if (id == null) return;
        final Context app = ctx.getApplicationContext();
        final ContentValues v = new ContentValues();
        if (finalText != null) v.put("final_text", finalText);
        if (originalText != null) v.put("original_text", originalText);
        v.put("outcome", outcome);
        if (note != null) v.put("note", note);
        WRITER.execute(() -> {
            try {
                db(app).update(TABLE, v, "id = ?", new String[]{id});
            } catch (RuntimeException e) {
                Log.e(TAG, "Failed to update history entry", e);
            }
        });
    }

    // --- Reading and management (call off the main thread) ------------------

    public static List<Entry> list(Context ctx, String query) {
        List<Entry> out = new ArrayList<>();
        String where = null;
        String[] args = null;
        if (query != null && !query.trim().isEmpty()) {
            String like = "%" + query.trim() + "%";
            where = "final_text LIKE ? OR raw_text LIKE ? OR original_text LIKE ?";
            args = new String[]{like, like, like};
        }
        try (Cursor c = db(ctx).query(TABLE, null, where, args, null, null,
                "created DESC", "500")) {
            while (c.moveToNext()) {
                Entry e = new Entry();
                e.id = c.getString(c.getColumnIndexOrThrow("id"));
                e.created = c.getLong(c.getColumnIndexOrThrow("created"));
                e.durationMs = c.getLong(c.getColumnIndexOrThrow("duration_ms"));
                e.source = c.getString(c.getColumnIndexOrThrow("source"));
                e.rawText = c.getString(c.getColumnIndexOrThrow("raw_text"));
                e.finalText = c.getString(c.getColumnIndexOrThrow("final_text"));
                e.originalText = c.getString(c.getColumnIndexOrThrow("original_text"));
                e.outcome = c.getString(c.getColumnIndexOrThrow("outcome"));
                e.note = c.getString(c.getColumnIndexOrThrow("note"));
                e.audio = c.getString(c.getColumnIndexOrThrow("audio"));
                out.add(e);
            }
        }
        return out;
    }

    public static void delete(Context ctx, Entry e) {
        File audio = audioFile(ctx, e);
        if (audio != null) audio.delete();
        db(ctx).delete(TABLE, "id = ?", new String[]{e.id});
    }

    public static void clear(Context ctx) {
        db(ctx).delete(TABLE, null, null);
        File[] files = audioDir(ctx).listFiles();
        if (files != null) for (File f : files) f.delete();
    }

    /** Total size of kept recordings, for the settings screen. */
    public static long audioBytes(Context ctx) {
        long total = 0;
        File[] files = audioDir(ctx).listFiles();
        if (files != null) for (File f : files) total += f.length();
        return total;
    }

    /** Drops recordings older than the retention period; the text stays. */
    static void prune(Context ctx) {
        if (getAudioDays(ctx) <= KEEP_FOREVER) return;
        long cutoff = System.currentTimeMillis() - getAudioDays(ctx) * 24L * 60 * 60 * 1000;
        SQLiteDatabase db = db(ctx);
        try (Cursor c = db.query(TABLE, new String[]{"id", "audio"},
                "audio IS NOT NULL AND created < ?", new String[]{String.valueOf(cutoff)},
                null, null, null)) {
            while (c.moveToNext()) {
                new File(audioDir(ctx), c.getString(1)).delete();
                ContentValues v = new ContentValues();
                v.putNull("audio");
                db.update(TABLE, v, "id = ?", new String[]{c.getString(0)});
            }
        }
    }

    private static synchronized SQLiteDatabase db(Context ctx) {
        if (helper == null) helper = new Helper(ctx.getApplicationContext());
        return helper.getWritableDatabase();
    }

    private static final class Helper extends SQLiteOpenHelper {
        Helper(Context ctx) {
            super(ctx, DB_NAME, null, 1);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE " + TABLE + " ("
                    + "id TEXT PRIMARY KEY, "
                    + "created INTEGER NOT NULL, "
                    + "duration_ms INTEGER NOT NULL DEFAULT 0, "
                    + "source TEXT NOT NULL, "
                    + "raw_text TEXT, "
                    + "final_text TEXT, "
                    + "original_text TEXT, "
                    + "outcome TEXT NOT NULL, "
                    + "note TEXT, "
                    + "audio TEXT)");
            db.execSQL("CREATE INDEX entries_created ON " + TABLE + " (created)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            // Version 1 is the first schema.
        }
    }
}
