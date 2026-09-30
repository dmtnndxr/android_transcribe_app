package dev.notune.transcribe;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

/** Settings shared by the app process and the separate IME process. */
public final class PunctuationPrefs {
    private static final String TAG = "PunctuationPrefs";
    private static final String FILE_NAME = "punctuation_keys";

    /** Display order in both the settings dialog and the keyboard row. */
    public static final String[] AVAILABLE = {".", ",", "?", "!", ":", ";"};

    private PunctuationPrefs() { }

    public static Set<String> getSelected(Context context) {
        File file = new File(context.getFilesDir(), FILE_NAME);
        String value = ".,?";
        if (file.exists()) {
            try (FileInputStream input = new FileInputStream(file)) {
                byte[] bytes = new byte[(int) file.length()];
                int offset = 0;
                while (offset < bytes.length) {
                    int read = input.read(bytes, offset, bytes.length - offset);
                    if (read < 0) break;
                    offset += read;
                }
                value = new String(bytes, 0, offset, StandardCharsets.UTF_8);
            } catch (IOException e) {
                Log.e(TAG, "Failed to read punctuation keys", e);
            }
        }

        Set<String> selected = new LinkedHashSet<>();
        for (String symbol : AVAILABLE) {
            if (value.contains(symbol)) selected.add(symbol);
        }
        return selected;
    }

    public static boolean saveSelected(Context context, Set<String> selected) {
        StringBuilder value = new StringBuilder();
        for (String symbol : AVAILABLE) {
            if (selected.contains(symbol)) value.append(symbol);
        }
        File file = new File(context.getFilesDir(), FILE_NAME);
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(value.toString().getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to save punctuation keys", e);
            return false;
        }
    }
}
