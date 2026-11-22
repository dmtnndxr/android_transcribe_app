package dev.notune.transcribe;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.text.format.DateUtils;
import android.util.Log;

import java.io.File;

/**
 * "Transcribe again" in the history. A foreground service, because a long
 * recording takes a while and the user will leave the screen meanwhile.
 */
public class RetranscribeService extends Service {

    private static final String TAG = "RetranscribeService";
    private static final String ACTION_CANCEL = "dev.notune.transcribe.action.CANCEL_RETRANSCRIBE";
    private static final String EXTRA_ID = "id";
    private static final int NOTIFICATION_ID = 3;

    // Mirrored in src/retranscribe.rs.
    private static final int RESULT_DONE = 0;
    private static final int RESULT_CANCELED = 1;
    private static final int RESULT_FAILED = 2;

    static {
        try {
            System.loadLibrary("c++_shared");
            System.loadLibrary("android_transcribe_app");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native libraries", e);
        }
    }

    // For the history screen, which lives in the same process. Main thread only.
    private static String runningId;
    private static long doneMs;
    private static long totalMs;
    private static Runnable listener;

    private final Handler main = new Handler(Looper.getMainLooper());
    private HistoryStore.Entry entry;
    private volatile String latestText = "";
    /** The longest text known for an entry that wasn't fully recognized. */
    private int savedLength;

    static void start(Context ctx, String id) {
        ctx.startForegroundService(new Intent(ctx, RetranscribeService.class).putExtra(EXTRA_ID, id));
    }

    static void cancel(Context ctx) {
        if (runningId == null) return;
        ctx.startService(new Intent(ctx, RetranscribeService.class).setAction(ACTION_CANCEL));
    }

    static void setListener(Runnable r) {
        listener = r;
    }

    static String runningId() {
        return runningId;
    }

    /** "03:00 / 40:12", or null before the first minute is done. */
    static String progress() {
        return totalMs > 0 ? clock(doneMs) + " / " + clock(totalMs) : null;
    }

    private static String clock(long ms) {
        return DateUtils.formatElapsedTime(ms / 1000);
    }

    private static void changed() {
        if (listener != null) listener.run();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            if (runningId == null) stopSelf();
            else cancelNative();
            return START_NOT_STICKY;
        }
        String id = intent == null ? null : intent.getStringExtra(EXTRA_ID);
        // startForegroundService requires this even if there turns out to be nothing to do.
        try {
            RecordingNotification.createChannel(this);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, buildNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, buildNotification());
            }
        } catch (RuntimeException e) {
            DebugLog.error(this, "retranscribe: foreground start failed", e);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (runningId != null) return START_NOT_STICKY;
        if (id == null) {
            finishService();
            return START_NOT_STICKY;
        }
        runningId = id;
        doneMs = 0;
        totalMs = 0;
        prepareNative();
        changed();
        new Thread(() -> work(id), "retranscribe").start();
        return START_NOT_STICKY;
    }

    private void work(String id) {
        PowerManager.WakeLock lock = null;
        int result = RESULT_FAILED;
        long started = System.currentTimeMillis();
        try {
            lock = WakeLocks.acquire(this);
            entry = HistoryStore.get(this, id);
            File audio = entry == null ? null : HistoryStore.audioFile(this, entry);
            if (WavFiles.hasAudio(audio)) {
                savedLength = entry.rawText == null ? 0 : entry.rawText.length();
                DebugLog.event(this, "retranscribe start: " + WavFiles.durationMs(audio) + " ms");
                result = transcribeNative(audio.getAbsolutePath());
                if (result == RESULT_DONE) {
                    HistoryStore.retranscribed(this, entry, latestText);
                } else if (result == RESULT_FAILED) {
                    HistoryStore.setNote(this, id, getString(R.string.history_retranscribe_failed));
                }
            }
        } catch (RuntimeException e) {
            DebugLog.error(this, "retranscribe failed", e);
        } finally {
            WakeLocks.release(lock);
            DebugLog.event(this, "retranscribe end: "
                    + (result == RESULT_DONE ? "done" : result == RESULT_CANCELED ? "canceled" : "failed")
                    + ", " + latestText.length() + " chars, "
                    + (System.currentTimeMillis() - started) + " ms");
            // Reload the history only once the result is in it.
            HistoryStore.afterWrites(() -> main.post(() -> {
                runningId = null;
                finishService();
                changed();
            }));
        }
    }

    private void finishService() {
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    // Called from Rust on the worker thread, after each minute.
    public void onTranscriptProgress(String text, long done, long total) {
        latestText = text;
        // What there is so far survives a kill, but never replaces better text.
        if (entry.recognition != HistoryStore.RECOGNIZED_FULL
                && text.length() > savedLength && done < total) {
            savedLength = text.length();
            HistoryStore.progress(this, entry.id, text);
        }
        main.post(() -> {
            if (runningId == null) return;
            doneMs = done;
            totalMs = total;
            getSystemService(android.app.NotificationManager.class)
                    .notify(NOTIFICATION_ID, buildNotification());
            changed();
        });
    }

    // Called from Rust while the model loads.
    public void onStatusUpdate(String status) {
        if (status.startsWith("Error")) DebugLog.event(this, "retranscribe status: " + status);
    }

    /** Android 15+ limits this type of service to 6 hours a day. */
    @Override
    public void onTimeout(int startId, int fgsType) {
        DebugLog.event(this, "retranscribe: foreground time limit");
        cancelNative();
        finishService();
    }

    private Notification buildNotification() {
        PendingIntent cancel = PendingIntent.getService(this, 0,
                new Intent(this, RetranscribeService.class).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, HistoryActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(this, RecordingNotification.CHANNEL)
                .setSmallIcon(R.drawable.ic_history)
                .setContentTitle(getString(R.string.notif_transcribing))
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_close),
                        getString(R.string.ime_cancel), cancel).build());
        if (progress() != null) b.setContentText(progress());
        return b.build();
    }

    private native void prepareNative();
    private native int transcribeNative(String path);
    private native void cancelNative();
}
