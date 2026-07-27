package dev.notune.transcribe;

import android.content.Context;
import android.util.Log;

/**
 * Android-facing wrapper around {@link PostProcessClient}: reads the saved
 * settings and runs the request off the main thread.
 *
 * The request itself — URL building, prompt templating, parsing, error mapping —
 * lives in {@link PostProcessClient}, which has no Android dependencies so it
 * can be exercised by the JVM tests in {@code tools/verify}. Keep this class
 * thin; logic added here is logic that can't be tested without a device.
 */
public final class PostProcessor {
    private static final String TAG = "PostProcessor";

    public interface Callback {
        /** Called on a background thread with the rewritten text. */
        void onSuccess(String text);
        /** Called on a background thread with a short, user-facing reason. */
        void onFailure(String message);
    }

    private PostProcessor() {}

    /** Runs the request on a background thread. */
    public static void processAsync(Context ctx, String transcript, Callback cb) {
        // Snapshot the config on the caller's thread; the worker only touches
        // the values, never the Context.
        final String baseUrl = PostProcessPrefs.getBaseUrl(ctx);
        final String apiKey = PostProcessPrefs.getApiKey(ctx);
        final String model = PostProcessPrefs.getModel(ctx);
        final String prompt = PostProcessPrefs.getPrompt(ctx);

        new Thread(() -> {
            try {
                cb.onSuccess(PostProcessClient.process(
                        baseUrl, apiKey, model, prompt, transcript));
            } catch (PostProcessClient.PostProcessException e) {
                Log.w(TAG, "Post-processing failed", e);
                cb.onFailure(e.getMessage());
            } catch (Throwable t) {
                // Never let a post-processing bug take down the IME — the
                // caller falls back to the raw transcription.
                Log.e(TAG, "Unexpected post-processing error", t);
                cb.onFailure(t.getClass().getSimpleName());
            }
        }, "post-process").start();
    }

    /** Blocking convenience overload that reads the current settings. */
    public static String process(Context ctx, String transcript)
            throws PostProcessClient.PostProcessException {
        return PostProcessClient.process(
                PostProcessPrefs.getBaseUrl(ctx),
                PostProcessPrefs.getApiKey(ctx),
                PostProcessPrefs.getModel(ctx),
                PostProcessPrefs.getPrompt(ctx),
                transcript);
    }
}
