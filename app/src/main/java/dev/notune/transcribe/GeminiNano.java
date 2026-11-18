package dev.notune.transcribe;

import android.content.Context;

import com.google.mlkit.common.MlKit;
import com.google.mlkit.genai.common.DownloadCallback;
import com.google.mlkit.genai.common.FeatureStatus;
import com.google.mlkit.genai.common.GenAiException;
import com.google.mlkit.genai.common.GenAiException.ErrorCode;
import com.google.mlkit.genai.prompt.Candidate;
import com.google.mlkit.genai.prompt.GenerateContentRequest;
import com.google.mlkit.genai.prompt.Generation;
import com.google.mlkit.genai.prompt.TextPart;
import com.google.mlkit.genai.prompt.java.GenerativeModelFutures;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Blocking adapter for ML Kit, used only on worker threads. */
final class GeminiNano implements AutoCloseable {
    private static final int STATUS_TIMEOUT_SECONDS = 5;
    private static final int INFERENCE_TIMEOUT_SECONDS = 15;
    private static boolean initialized;
    private final GenerativeModelFutures model;

    GeminiNano(Context context) {
        initialize(context);
        model = GenerativeModelFutures.from(Generation.INSTANCE.getClient());
    }

    private static synchronized void initialize(Context context) {
        // The app disables ML Kit's automatic initializer so each process
        // initializes once, only when the user uses Gemini Nano.
        if (initialized) return;
        MlKit.initialize(context.getApplicationContext());
        initialized = true;
    }

    int checkStatus() throws InterruptedException, ExecutionException, TimeoutException {
        return await(model.checkStatus(), deadlineAfter(STATUS_TIMEOUT_SECONDS));
    }

    /** Called only after the settings screen has obtained download consent. */
    void download(DownloadCallback callback)
            throws InterruptedException, ExecutionException, TimeoutException {
        // AICore can also provision the model independently of this screen.
        if (checkStatus() == FeatureStatus.DOWNLOADABLE) {
            ensureRunning();
            await(model.download(callback), deadlineAfter(15 * 60));
        }
    }

    static String process(Context context, String template, String transcript)
            throws PostProcessClient.PostProcessException {
        if (transcript == null || transcript.trim().isEmpty()) {
            throw failure(context, R.string.pp_nano_too_short);
        }
        String prompt = GeminiNanoPrompt.build(template, transcript);
        if (prompt == null) throw failure(context, R.string.pp_nano_too_long);

        long deadline = deadlineAfter(INFERENCE_TIMEOUT_SECONDS);
        try (GeminiNano nano = new GeminiNano(context)) {
            ensureRunning();
            int status = await(nano.model.checkStatus(), deadline);
            switch (status) {
                case FeatureStatus.AVAILABLE: break;
                case FeatureStatus.DOWNLOADABLE:
                    throw failure(context, R.string.pp_nano_downloadable);
                case FeatureStatus.DOWNLOADING:
                    throw failure(context, R.string.pp_nano_downloading);
                default:
                    throw failure(context, R.string.pp_nano_unavailable);
            }

            GenerateContentRequest.Builder requestBuilder =
                    new GenerateContentRequest.Builder(new TextPart(prompt));
            requestBuilder.setTemperature(0.2f);
            int limit = await(nano.model.getTokenLimit(), deadline);
            int tokens = await(nano.model.countTokens(requestBuilder.build()), deadline)
                    .getTotalTokens();
            int outputBudget = GeminiNanoPrompt.outputBudget(limit, tokens);
            if (outputBudget == 0) throw failure(context, R.string.pp_nano_too_long);
            requestBuilder.setMaxOutputTokens(outputBudget);

            ensureRunning();
            List<Candidate> candidates = await(
                    nano.model.generateContent(requestBuilder.build()), deadline)
                    .getCandidates();
            if (candidates.isEmpty()) throw failure(context, R.string.pp_nano_invalid_output);
            Candidate result = candidates.get(0);
            Integer reason = result.getFinishReason();
            if (reason != null && reason == Candidate.FinishReason.MAX_TOKENS) {
                throw failure(context, R.string.pp_nano_too_long);
            }
            String text = result.getText();
            if ((reason != null && reason != Candidate.FinishReason.STOP)
                    || !GeminiNanoPrompt.isValidOutput(text) || prompt.trim().equals(text.trim())) {
                throw failure(context, R.string.pp_nano_invalid_output);
            }
            return text.trim();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failure(context, R.string.pp_nano_canceled);
        } catch (ExecutionException | TimeoutException | RuntimeException | LinkageError e) {
            throw failure(context, errorMessage(e));
        }
    }

    private static PostProcessClient.PostProcessException failure(Context context, int message) {
        return new PostProcessClient.PostProcessException(context.getString(message));
    }

    /** Uses fixed messages so SDK diagnostics cannot echo private text into the UI or logs. */
    static int errorMessage(Throwable error) {
        if (error instanceof ExecutionException) error = error.getCause();
        if (error instanceof TimeoutException) return R.string.pp_nano_timeout;
        if (error instanceof CancellationException) return R.string.pp_nano_canceled;
        if (!(error instanceof GenAiException)) return R.string.pp_nano_temporary;
        switch (((GenAiException) error).getErrorCode()) {
            case ErrorCode.BACKGROUND_USE_BLOCKED: return R.string.pp_nano_background;
            case ErrorCode.BUSY: return R.string.pp_nano_busy;
            case ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED: return R.string.pp_nano_quota;
            case ErrorCode.NOT_ENOUGH_DISK_SPACE: return R.string.pp_nano_disk;
            case ErrorCode.AICORE_INCOMPATIBLE:
            case ErrorCode.NEEDS_SYSTEM_UPDATE: return R.string.pp_nano_update;
            case ErrorCode.NOT_SUPPORTED: return R.string.pp_nano_unavailable;
            case ErrorCode.REQUEST_TOO_LARGE: return R.string.pp_nano_too_long;
            case ErrorCode.REQUEST_TOO_SMALL: return R.string.pp_nano_too_short;
            case ErrorCode.RESPONSE_PROCESSING_ERROR:
            case ErrorCode.RESPONSE_GENERATION_ERROR: return R.string.pp_nano_invalid_output;
            case ErrorCode.CANCELLED: return R.string.pp_nano_canceled;
            default: return R.string.pp_nano_temporary;
        }
    }

    private static long deadlineAfter(int seconds) {
        return System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
    }

    private static void ensureRunning() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
    }

    private static <T> T await(Future<T> task, long deadline)
            throws InterruptedException, ExecutionException, TimeoutException {
        try {
            ensureRunning();
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new TimeoutException();
            return task.get(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException | TimeoutException e) {
            task.cancel(true);
            throw e;
        }
    }

    @Override
    public void close() {
        model.getGenerativeModel().close();
    }
}
