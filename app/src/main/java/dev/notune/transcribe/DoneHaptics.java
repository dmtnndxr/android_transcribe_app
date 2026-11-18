package dev.notune.transcribe;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;

import java.io.File;

/**
 * A short vibration when the voice keyboard has finished a dictation —
 * recognition and, if used, AI post-processing — so the user knows the text
 * is in without looking at the screen. Uses the vibrator directly rather than
 * view haptics, because the keyboard may already be hidden by then.
 */
final class DoneHaptics {

    private static final String TAG = "DoneHaptics";

    /** Marker file: present when the user turned the vibration off. */
    static final String NO_VIBRATE_MARKER = "no_vibrate_done";

    private DoneHaptics() {}

    static boolean isEnabled(Context context) {
        return !new File(context.getFilesDir(), NO_VIBRATE_MARKER).exists();
    }

    static void play(Context context) {
        if (!isEnabled(context)) return;
        try {
            Vibrator vibrator;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager vm = context.getSystemService(VibratorManager.class);
                vibrator = vm != null ? vm.getDefaultVibrator() : null;
            } else {
                vibrator = context.getSystemService(Vibrator.class);
            }
            if (vibrator == null || !vibrator.hasVibrator()) return;
            VibrationEffect effect = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
                    : VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE);
            vibrator.vibrate(effect);
        } catch (RuntimeException e) {
            Log.w(TAG, "Vibration failed", e);
        }
    }
}
