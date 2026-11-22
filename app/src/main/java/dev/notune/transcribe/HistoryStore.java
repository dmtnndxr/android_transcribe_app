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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
    public static final String OUTCOME_RECORDING = "recording";
    /** The process or service went away before the dictation finished. */
    public static final String OUTCOME_INTERRUPTED = "interrupted";
    public static final String OUTCOME_NO_SPEECH = "no_speech";

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

    // How much of the recording made it into text. Kept apart from the outcome,
    // which says where the text went.
    public static final int RECOGNIZED_NONE = 0;
    public static final int RECOGNIZED_PARTLY = 1;
    public static final int RECOGNIZED_FULL = 2;
    private static final String TEMP_DIR = "capture_tmp";

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
        public int recognition;
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

    /**
     * Where to record when the history doesn't keep audio: recognition reads
     * the file, which is deleted right after. Not the cache, which Android may
     * clear in the middle of a recording.
     */
    static File newTempAudioFile(Context ctx) {
        File dir = new File(ctx.getFilesDir(), TEMP_DIR);
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, UUID.randomUUID() + ".wav");
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
        v.put("recognition", RECOGNIZED_FULL);
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

    /**
     * Records a dictation that has just started, so that its audio is in the
     * history whatever happens next. Returns the id, or null without audio.
     */
    public static String begin(Context ctx, String source, File audio) {
        if (audio == null) return null;
        final Context app = ctx.getApplicationContext();
        final String id = UUID.randomUUID().toString();
        final ContentValues v = new ContentValues();
        v.put("id", id);
        v.put("created", System.currentTimeMillis());
        v.put("source", source);
        v.put("raw_text", "");
        v.put("final_text", "");
        v.put("outcome", OUTCOME_RECORDING);
        v.put("recognition", RECOGNIZED_NONE);
        v.put("audio", audio.getName());
        WRITER.execute(() -> {
            try {
                db(app).insert(TABLE, null, v);
                prune(app);
            } catch (RuntimeException e) {
                DebugLog.error(app, "history begin failed", e);
            }
        });
        return id;
    }

    /** Capture ended; what it contains isn't known yet. */
    public static void stopped(Context ctx, String id, long durationMs, String outcome) {
        ContentValues v = new ContentValues();
        v.put("duration_ms", durationMs);
        v.put("outcome", outcome);
        update(ctx, id, v);
    }

    public static void transcribed(Context ctx, String id, String text) {
        ContentValues v = new ContentValues();
        v.put("raw_text", text);
        v.put("final_text", text);
        v.put("outcome", OUTCOME_PENDING);
        v.put("recognition", RECOGNIZED_FULL);
        v.putNull("note");
        update(ctx, id, v);
    }

    /** The text so far of a recording that is still being transcribed. */
    static void progress(Context ctx, String id, String text) {
        // Nothing recognized in the first minutes is not "partly recognized".
        if (text.isEmpty()) return;
        ContentValues v = new ContentValues();
        v.put("raw_text", text);
        v.put("final_text", text);
        v.put("recognition", RECOGNIZED_PARTLY);
        update(ctx, id, v);
    }

    /** All of it was transcribed and there are no words in it. */
    static void noSpeech(Context ctx, String id) {
        ContentValues v = new ContentValues();
        v.put("outcome", OUTCOME_NO_SPEECH);
        v.put("recognition", RECOGNIZED_FULL);
        update(ctx, id, v);
    }

    static void setNote(Context ctx, String id, String note) {
        ContentValues v = new ContentValues();
        v.put("note", note);
        update(ctx, id, v);
    }

    /** The result of "Transcribe again"; {@code before} is the entry as it was. */
    static void retranscribed(Context ctx, Entry before, String text) {
        boolean wasFull = before.recognition == RECOGNIZED_FULL;
        boolean hadText = before.rawText != null && !before.rawText.isEmpty();
        // Never trade text for nothing.
        if (text.isEmpty() && hadText) return;
        ContentValues v = new ContentValues();
        v.put("recognition", RECOGNIZED_FULL);
        v.put("raw_text", text);
        // What AI or a voice edit made of a complete text stays.
        if (!wasFull || !hadText || before.rawText.equals(before.finalText)) {
            v.put("final_text", text);
        }
        if (!wasFull || !hadText) {
            v.put("outcome", text.isEmpty() ? OUTCOME_NO_SPEECH : OUTCOME_KEPT);
            v.putNull("note");
        }
        update(ctx, before.id, v);
    }

    /** Runs {@code then} on the writer thread, after the writes queued so far. */
    static void afterWrites(Runnable then) {
        WRITER.execute(then);
    }

    private static void update(Context ctx, String id, ContentValues v) {
        if (id == null) return;
        final Context app = ctx.getApplicationContext();
        WRITER.execute(() -> {
            try {
                db(app).update(TABLE, v, "id = ?", new String[]{id});
            } catch (RuntimeException e) {
                DebugLog.error(app, "history update failed", e);
            }
        });
    }

    /** Forgets a recording the user threw away, or one with nothing in it. */
    public static void discard(Context ctx, String id, File audio) {
        if (audio != null) audio.delete();
        if (id == null) return;
        final Context app = ctx.getApplicationContext();
        WRITER.execute(() -> {
            try {
                db(app).delete(TABLE, "id = ?", new String[]{id});
            } catch (RuntimeException e) {
                DebugLog.error(app, "history discard failed", e);
            }
        });
    }

    /**
     * Brings back what a killed keyboard process left behind: entries stuck
     * mid-dictation, and recordings no entry points to (from older versions).
     * Run once when the keyboard process starts; anything newer than
     * {@code processStart} is a live dictation and is left alone.
     */
    public static void recover(Context ctx, long processStart) {
        final Context app = ctx.getApplicationContext();
        WRITER.execute(() -> {
            try {
                recoverUnfinished(app, processStart);
                adoptStrayRecordings(app, processStart);
                deleteStaleTempFiles(app, processStart);
            } catch (RuntimeException e) {
                DebugLog.error(app, "history recovery failed", e);
            }
        });
    }

    private static void recoverUnfinished(Context ctx, long before) {
        SQLiteDatabase db = db(ctx);
        try (Cursor c = db.query(TABLE, new String[]{"id", "audio", "raw_text"},
                "outcome IN (?, ?) AND source != ? AND created < ?",
                new String[]{OUTCOME_RECORDING, OUTCOME_PENDING, SOURCE_OTHER_KEYBOARD,
                        String.valueOf(before)},
                null, null, null)) {
            while (c.moveToNext()) {
                String id = c.getString(0);
                File audio = c.isNull(1) ? null : new File(audioDir(ctx), c.getString(1));
                boolean hasText = !c.isNull(2) && !c.getString(2).isEmpty();
                if (!hasText && !WavFiles.hasAudio(audio)) {
                    if (audio != null) audio.delete();
                    db.delete(TABLE, "id = ?", new String[]{id});
                    continue;
                }
                ContentValues v = new ContentValues();
                v.put("outcome", OUTCOME_INTERRUPTED);
                if (WavFiles.hasAudio(audio)) {
                    repair(ctx, audio);
                    v.put("duration_ms", WavFiles.durationMs(audio));
                }
                db.update(TABLE, v, "id = ?", new String[]{id});
                DebugLog.event(ctx, "history: recovered an interrupted dictation, "
                        + WavFiles.durationMs(audio) + " ms");
            }
        }
    }

    private static void adoptStrayRecordings(Context ctx, long before) {
        // List the files first: an entry is always inserted before its file can be this old.
        File[] files = audioDir(ctx).listFiles();
        if (files == null) return;
        SQLiteDatabase db = db(ctx);
        Set<String> known = new HashSet<>();
        try (Cursor c = db.query(TABLE, new String[]{"audio"}, "audio IS NOT NULL",
                null, null, null, null)) {
            while (c.moveToNext()) known.add(c.getString(0));
        }
        for (File file : files) {
            if (known.contains(file.getName()) || file.lastModified() >= before) continue;
            if (!WavFiles.hasAudio(file)) {
                file.delete();
                continue;
            }
            if (file.getName().endsWith(".part")) {
                // Left by versions that wrote the file in one go after recording.
                File whole = new File(file.getPath().substring(0, file.getPath().length() - 5));
                if (whole.exists() || !file.renameTo(whole)) continue;
                file = whole;
            }
            repair(ctx, file);
            long duration = WavFiles.durationMs(file);
            ContentValues v = new ContentValues();
            v.put("id", UUID.randomUUID().toString());
            v.put("created", Math.max(0, file.lastModified() - duration));
            v.put("duration_ms", duration);
            v.put("source", SOURCE_DICTATION);
            v.put("raw_text", "");
            v.put("final_text", "");
            v.put("outcome", OUTCOME_INTERRUPTED);
            v.put("recognition", RECOGNIZED_NONE);
            v.put("audio", file.getName());
            db.insert(TABLE, null, v);
            DebugLog.event(ctx, "history: adopted a stray recording, " + duration + " ms");
        }
    }

    private static void deleteStaleTempFiles(Context ctx, long before) {
        File[] files = new File(ctx.getFilesDir(), TEMP_DIR).listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.lastModified() < before) file.delete();
        }
    }

    private static void repair(Context ctx, File audio) {
        try {
            WavFiles.repair(audio);
        } catch (IOException e) {
            DebugLog.error(ctx, "wav repair failed", e);
        }
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
                "created DESC")) {
            while (c.moveToNext()) {
                out.add(read(c));
            }
        }
        return out;
    }

    static Entry get(Context ctx, String id) {
        try (Cursor c = db(ctx).query(TABLE, null, "id = ?", new String[]{id}, null, null, null)) {
            return c.moveToFirst() ? read(c) : null;
        }
    }

    private static Entry read(Cursor c) {
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
        int recognition = c.getColumnIndexOrThrow("recognition");
        if (!c.isNull(recognition)) {
            e.recognition = c.getInt(recognition);
        } else {
            // Written by a version without the column.
            boolean recognized = (e.rawText != null && !e.rawText.isEmpty())
                    || OUTCOME_NO_SPEECH.equals(e.outcome);
            e.recognition = recognized ? RECOGNIZED_FULL : RECOGNIZED_NONE;
        }
        return e;
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

    /**
     * Drops recordings older than the retention period; the text stays. An
     * entry that was only a recording goes with it.
     */
    static void prune(Context ctx) {
        if (getAudioDays(ctx) <= KEEP_FOREVER) return;
        long cutoff = System.currentTimeMillis() - getAudioDays(ctx) * 24L * 60 * 60 * 1000;
        SQLiteDatabase db = db(ctx);
        try (Cursor c = db.query(TABLE, new String[]{"id", "audio", "raw_text"},
                "audio IS NOT NULL AND created < ?", new String[]{String.valueOf(cutoff)},
                null, null, null)) {
            while (c.moveToNext()) {
                new File(audioDir(ctx), c.getString(1)).delete();
                if (c.isNull(2) || c.getString(2).isEmpty()) {
                    db.delete(TABLE, "id = ?", new String[]{c.getString(0)});
                    continue;
                }
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
                    + "audio TEXT, "
                    + "recognition INTEGER)");
            db.execSQL("CREATE INDEX entries_created ON " + TABLE + " (created)");
        }

        /**
         * A column added without raising the schema version: older app versions
         * refuse to open a newer schema, and this one they simply ignore.
         */
        @Override
        public void onOpen(SQLiteDatabase db) {
            if (db.isReadOnly()) return;
            db.beginTransaction(); // both processes may get here at once
            try {
                boolean present = false;
                try (Cursor c = db.rawQuery("PRAGMA table_info(" + TABLE + ")", null)) {
                    while (c.moveToNext()) {
                        if ("recognition".equals(c.getString(1))) present = true;
                    }
                }
                if (!present) db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN recognition INTEGER");
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            // Version 1 is the first schema.
        }
    }
}
