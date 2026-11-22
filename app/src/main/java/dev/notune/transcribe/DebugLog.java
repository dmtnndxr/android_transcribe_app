package dev.notune.transcribe;

import android.app.ActivityManager;
import android.app.Application;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A small on-device log for "what happened to my dictation": recording start
 * and stop, muting, errors. It leaves the phone only when the user shares it.
 *
 * <p>Never log dictated or selected text, prompts, AI replies (including their
 * error messages, which can quote the request or the key), keys, or the app
 * being typed into.
 */
final class DebugLog {

    private static final String TAG = "BoiceLog";
    private static final long MAX_BYTES = 256 * 1024;
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor();

    private DebugLog() {}

    static void event(Context ctx, String message) {
        File dir = new File(ctx.getFilesDir(), "logs");
        String line = line(message);
        WRITER.execute(() -> append(dir, line));
    }

    /** Logs where it failed, not the exception's message, which may quote user text. */
    static void error(Context ctx, String what, Throwable t) {
        event(ctx, what + describe(t));
    }

    /** Synchronous: the process is about to die. */
    static void crash(Context ctx, Throwable t) {
        append(new File(ctx.getFilesDir(), "logs"), line("crash" + describe(t)));
    }

    private static String describe(Throwable t) {
        StringBuilder b = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            b.append("\n  ").append(c.getClass().getName());
            StackTraceElement[] stack = c.getStackTrace();
            for (int i = 0; i < Math.min(stack.length, 12); i++) {
                b.append("\n    at ").append(stack[i]);
            }
        }
        return b.toString();
    }

    private static String line(String message) {
        return time(System.currentTimeMillis()) + " " + processName() + "/" + Process.myPid()
                + " " + message + "\n";
    }

    private static String time(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).format(new Date(millis));
    }

    private static String processName() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return "app";
        String name = Application.getProcessName();
        int colon = name.indexOf(':');
        return colon < 0 ? "main" : name.substring(colon + 1);
    }

    // The app and keyboard processes both append; O_APPEND keeps lines whole.
    private static synchronized void append(File dir, String line) {
        dir.mkdirs();
        File log = new File(dir, "debug.log");
        if (log.length() > MAX_BYTES && !log.renameTo(new File(dir, "debug.1.log"))) {
            Log.w(TAG, "Can't rotate the log");
        }
        try (OutputStream out = new FileOutputStream(log, true)) {
            out.write(line.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.w(TAG, "Can't write the log", e);
        }
    }

    /** Builds the file to share: versions, why the processes last exited, the log. */
    static File export(Context ctx) throws Exception {
        StringBuilder head = new StringBuilder();
        head.append("Boice ").append(ctx.getPackageManager()
                .getPackageInfo(ctx.getPackageName(), 0).versionName).append('\n');
        head.append("Android ").append(Build.VERSION.RELEASE).append(", ")
                .append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            head.append("\n== process exits\n");
            ActivityManager am = ctx.getSystemService(ActivityManager.class);
            for (ApplicationExitInfo info : am.getHistoricalProcessExitReasons(null, 0, 20)) {
                head.append(time(info.getTimestamp())).append(' ').append(info.getProcessName())
                        .append(" reason=").append(exitReason(info.getReason()))
                        .append(" status=").append(info.getStatus())
                        .append(" importance=").append(info.getImportance())
                        .append(" rssMB=").append(info.getRss() / 1024).append('\n');
            }
        }
        head.append("\n== log\n");

        File dir = new File(ctx.getCacheDir(), "share");
        dir.mkdirs();
        File out = new File(dir, "boice-log.txt");
        try (OutputStream os = new FileOutputStream(out)) {
            os.write(head.toString().getBytes(StandardCharsets.UTF_8));
            for (String name : new String[]{"debug.1.log", "debug.log"}) {
                File part = new File(new File(ctx.getFilesDir(), "logs"), name);
                if (part.exists()) Files.copy(part.toPath(), os);
            }
        }
        return out;
    }

    private static String exitReason(int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_EXIT_SELF: return "exit-self";
            case ApplicationExitInfo.REASON_SIGNALED: return "signaled";
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "low-memory";
            case ApplicationExitInfo.REASON_CRASH: return "crash";
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "native-crash";
            case ApplicationExitInfo.REASON_ANR: return "anr";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "excessive-resources";
            case ApplicationExitInfo.REASON_USER_REQUESTED: return "user-requested";
            default: return String.valueOf(reason);
        }
    }
}
