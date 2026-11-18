package dev.notune.transcribe;

import android.content.Context;
import android.util.Log;

import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

/** Snapshots the selected provider and runs post-processing off the main thread. */
public final class PostProcessor {
    private static final String TAG = "PostProcessor";

    public interface Callback {
        /** Called on a background thread with the rewritten text. */
        void onSuccess(String text);
        /** Called on a background thread with a short, user-facing reason. */
        void onFailure(String message);
    }

    private PostProcessor() {}

    private interface Request {
        String run(String transcript) throws PostProcessClient.PostProcessException;
    }

    private static Request request(Context ctx, PostProcessPrefs.Provider provider) {
        final String prompt = PostProcessPrefs.getPrompt(ctx);
        if (provider == PostProcessPrefs.Provider.GEMINI_NANO) {
            Context appContext = ctx.getApplicationContext();
            return transcript -> GeminiNano.process(appContext, prompt, transcript);
        }
        final String baseUrl = PostProcessPrefs.getBaseUrl(ctx);
        final String apiKey = PostProcessPrefs.getApiKey(ctx);
        final String model = PostProcessPrefs.getModel(ctx);
        return transcript -> PostProcessClient.process(baseUrl, apiKey, model, prompt, transcript);
    }

    /** Runs the selected provider. Canceling the returned task interrupts Nano inference. */
    public static Future<?> processAsync(Context ctx, PostProcessPrefs.Provider provider,
                                         String transcript, Callback cb) {
        Request request = request(ctx, provider);
        boolean nano = provider == PostProcessPrefs.Provider.GEMINI_NANO;
        FutureTask<Void> task = new FutureTask<>(() -> {
            String output;
            try {
                output = request.run(transcript);
            } catch (PostProcessClient.PostProcessException e) {
                if (!nano) Log.w(TAG, "Post-processing failed", e);
                if (!Thread.currentThread().isInterrupted()) cb.onFailure(e.getMessage());
                return;
            } catch (Throwable t) {
                if (!nano) Log.e(TAG, "Unexpected post-processing error", t);
                if (!Thread.currentThread().isInterrupted()) {
                    cb.onFailure(nano ? ctx.getString(R.string.pp_nano_temporary)
                            : t.getClass().getSimpleName());
                }
                return;
            }
            if (!Thread.currentThread().isInterrupted()) cb.onSuccess(output);
        }, null);
        new Thread(task, "post-process").start();
        return task;
    }

    /** Applies a spoken command to a selection without using the cleanup template. */
    public static void editSelectionAsync(Context ctx, String instruction,
                                          String selectedText, Callback cb) {
        final String baseUrl = PostProcessPrefs.getBaseUrl(ctx);
        final String apiKey = PostProcessPrefs.getApiKey(ctx);
        final String model = SelectionEditPrefs.getModel(ctx);
        final String systemPrompt = SelectionEditPrefs.getSystemPrompt(ctx);

        new Thread(() -> {
            try {
                cb.onSuccess(PostProcessClient.editSelection(
                        baseUrl, apiKey, model, systemPrompt, instruction, selectedText));
            } catch (PostProcessClient.PostProcessException e) {
                Log.w(TAG, "Selection edit failed", e);
                cb.onFailure(e.getMessage());
            } catch (Throwable t) {
                Log.e(TAG, "Unexpected selection-edit error", t);
                cb.onFailure(t.getClass().getSimpleName());
            }
        }, "selection-edit").start();
    }

    /** Blocking convenience overload that reads the current settings. */
    public static String process(Context ctx, String transcript)
            throws PostProcessClient.PostProcessException {
        return request(ctx, PostProcessPrefs.getProvider(ctx)).run(transcript);
    }
}
