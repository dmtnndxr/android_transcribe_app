package dev.notune.transcribe;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.os.PersistableBundle;
import android.widget.Toast;

import java.io.File;

/**
 * Keeps a finished transcription from being lost when there is no text field
 * to put it in — the user minimized the app or moved to another field while
 * it was being transcribed. Unless the user turned it off, the text goes to
 * the clipboard with a short toast saying so. The voice keyboard additionally
 * offers it for insertion the next time it opens.
 */
public final class TranscriptRescue {

    /** Marker file: present when the user turned clipboard copying off. */
    static final String NO_CLIPBOARD_MARKER = "no_clipboard_rescue";

    private TranscriptRescue() {}

    static boolean isClipboardEnabled(Context context) {
        return !new File(context.getFilesDir(), NO_CLIPBOARD_MARKER).exists();
    }

    /**
     * Copies {@code text} to the clipboard if the user allows it.
     *
     * @param keyboardOffersIt the voice keyboard will offer the text when it
     *                         next opens, so a toast can point there when
     *                         nothing was copied.
     */
    public static void rescue(Context context, String text, boolean keyboardOffersIt) {
        if (isClipboardEnabled(context)) {
            copyToClipboard(context, text);
            // Android 13+ has its own copy confirmation, but OEM builds vary
            // and the user is in another app by now, so say it explicitly
            // even if that occasionally doubles up.
            Toast.makeText(context, R.string.transcript_copied, Toast.LENGTH_LONG).show();
        } else if (keyboardOffersIt) {
            Toast.makeText(context, R.string.transcript_kept, Toast.LENGTH_LONG).show();
        }
    }

    private static void copyToClipboard(Context context, String text) {
        ClipboardManager clipboard = context.getSystemService(ClipboardManager.class);
        if (clipboard == null) return;
        ClipData clip = ClipData.newPlainText(context.getString(R.string.transcript_clip_label), text);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Dictation is often private: keep it out of the system's
            // clipboard preview.
            PersistableBundle extras = new PersistableBundle();
            extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
            clip.getDescription().setExtras(extras);
        }
        clipboard.setPrimaryClip(clip);
    }
}
