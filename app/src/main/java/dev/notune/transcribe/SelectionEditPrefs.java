package dev.notune.transcribe;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Settings specific to voice editing of selected text. */
public final class SelectionEditPrefs {
    private static final String TAG = "SelectionEditPrefs";
    private static final String MODEL = "selection_edit_model";
    private static final String PROMPT = "selection_edit_prompt";

    public static final String DEFAULT_SYSTEM_PROMPT =
            PostProcessClient.DEFAULT_EDIT_SELECTION_PROMPT;

    private SelectionEditPrefs() {}

    /** Blank override means "follow the AI post-processing model". */
    public static String getModelOverride(Context ctx) {
        return read(ctx, MODEL);
    }

    public static String getModel(Context ctx) {
        String override = getModelOverride(ctx);
        return override.isEmpty() ? PostProcessPrefs.getModel(ctx) : override;
    }

    public static void setModelOverride(Context ctx, String value) {
        write(ctx, MODEL, value);
    }

    public static String getSystemPrompt(Context ctx) {
        String stored = read(ctx, PROMPT);
        return stored.isEmpty() ? DEFAULT_SYSTEM_PROMPT : stored;
    }

    public static void setSystemPrompt(Context ctx, String value) {
        write(ctx, PROMPT, value);
    }

    public static boolean isConfigured(Context ctx) {
        return PostProcessPrefs.isEnabled(ctx)
                && !PostProcessPrefs.getBaseUrl(ctx).isEmpty()
                && !getModel(ctx).isEmpty();
    }

    private static String read(Context ctx, String name) {
        File file = new File(ctx.getFilesDir(), name);
        if (!file.exists()) return "";
        try {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            Log.w(TAG, "Failed to read " + name, e);
            return "";
        }
    }

    private static void write(Context ctx, String name, String value) {
        File file = new File(ctx.getFilesDir(), name);
        try {
            String trimmed = value == null ? "" : value.trim();
            if (trimmed.isEmpty()) {
                file.delete();
            } else {
                Files.write(file.toPath(), trimmed.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to write " + name, e);
        }
    }
}
