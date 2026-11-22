package dev.notune.transcribe;

import android.content.Context;
import android.os.PowerManager;

/** Keeps the CPU up while a long recording is transcribed with the screen off. */
final class WakeLocks {

    private static final long MAX_MS = 3 * 60 * 60 * 1000L;

    private WakeLocks() {}

    static PowerManager.WakeLock acquire(Context ctx) {
        PowerManager.WakeLock lock = ctx.getSystemService(PowerManager.class)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "boice:transcribe");
        lock.setReferenceCounted(false);
        lock.acquire(MAX_MS);
        return lock;
    }

    static void release(PowerManager.WakeLock lock) {
        if (lock != null && lock.isHeld()) lock.release();
    }
}
