package dev.notune.transcribe;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Settings for LLM post-processing of transcriptions.
 *
 * Stored as small files in the app's private files dir rather than
 * SharedPreferences, matching every other setting here (auto_record,
 * theme_mode, model_threads, …). The reason is the same: the IME runs in a
 * separate process (":ime"), where SharedPreferences are not reliably shared.
 *
 * Post-processing is off by default and never touches the normal mic — it only
 * runs when the user taps the dedicated button on the voice keyboard, so the
 * plain transcription path stays fully offline.
 *
 * Note on the API key: it lives in the app's private storage in plain text,
 * readable by this app only (and by root / an unlocked-bootloader backup).
 * That is the same protection SharedPreferences would give; it is not
 * hardware-backed. Use a key scoped to this device where the provider allows it.
 */
public final class PostProcessPrefs {
    private static final String TAG = "PostProcessPrefs";

    /** Marker file: present = post-processing enabled. */
    private static final String ENABLED = "pp_enabled";
    private static final String BASE_URL = "pp_base_url";
    private static final String API_KEY = "pp_api_key";
    private static final String MODEL = "pp_model";
    private static final String PROMPT = "pp_prompt";
    private static final String NANO_PROVIDER = "pp_use_gemini_nano";

    public enum Provider { OPENAI_COMPATIBLE, GEMINI_NANO }

    /**
     * Used when the user hasn't written their own. Defined in
     * {@link PostProcessClient} so the request builder and its JVM tests share
     * one copy; aliased here because this is where the UI reaches for it.
     */
    public static final String DEFAULT_PROMPT = PostProcessClient.DEFAULT_PROMPT;

    private PostProcessPrefs() {}

    public static boolean isEnabled(Context ctx) {
        return new File(ctx.getFilesDir(), ENABLED).exists();
    }

    public static void setEnabled(Context ctx, boolean enabled) {
        setMarker(ctx, ENABLED, enabled);
    }

    public static Provider getProvider(Context ctx) {
        return new File(ctx.getFilesDir(), NANO_PROVIDER).exists()
                ? Provider.GEMINI_NANO : Provider.OPENAI_COMPATIBLE;
    }

    public static boolean setProvider(Context ctx, Provider provider) {
        setMarker(ctx, NANO_PROVIDER, provider == Provider.GEMINI_NANO);
        return getProvider(ctx) == provider;
    }

    private static void setMarker(Context ctx, String name, boolean enabled) {
        File marker = new File(ctx.getFilesDir(), name);
        if (enabled) {
            try {
                marker.createNewFile();
            } catch (IOException e) {
                Log.e(TAG, "Failed to create " + name, e);
            }
        } else {
            marker.delete();
        }
    }

    public static String getBaseUrl(Context ctx) { return read(ctx, BASE_URL); }
    public static String getApiKey(Context ctx)  { return read(ctx, API_KEY); }
    public static String getModel(Context ctx)   { return read(ctx, MODEL); }

    /** The user's prompt template, or {@link #DEFAULT_PROMPT} if none is set. */
    public static String getPrompt(Context ctx) {
        String stored = read(ctx, PROMPT);
        return stored.isEmpty() ? DEFAULT_PROMPT : stored;
    }

    public static void setBaseUrl(Context ctx, String v) { write(ctx, BASE_URL, v); }
    public static void setApiKey(Context ctx, String v)  { write(ctx, API_KEY, v); }
    public static void setModel(Context ctx, String v)   { write(ctx, MODEL, v); }
    public static void setPrompt(Context ctx, String v)  { write(ctx, PROMPT, v); }

    /**
     * Checks the saved setup. Nano compatibility and asset status are checked
     * at request time. Local OpenAI-compatible endpoints can omit the API key.
     */
    public static boolean isConfigured(Context ctx) {
        return isEnabled(ctx)
                && (getProvider(ctx) == Provider.GEMINI_NANO
                    || (!getBaseUrl(ctx).isEmpty() && !getModel(ctx).isEmpty()));
    }

    private static String read(Context ctx, String name) {
        File f = new File(ctx.getFilesDir(), name);
        if (!f.exists()) return "";
        try {
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            Log.w(TAG, "Failed to read " + name, e);
            return "";
        }
    }

    private static void write(Context ctx, String name, String value) {
        File f = new File(ctx.getFilesDir(), name);
        try {
            if (value == null || value.trim().isEmpty()) {
                f.delete();
            } else {
                Files.write(f.toPath(), value.trim().getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to write " + name, e);
        }
    }
}
