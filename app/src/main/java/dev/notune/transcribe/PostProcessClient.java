package dev.notune.transcribe;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The OpenAI-compatible chat-completions call, with no Android dependencies.
 *
 * "OpenAI-compatible" is the de-facto interface exposed by OpenAI itself and by
 * Ollama, LM Studio, llama.cpp's server, vLLM, Groq, OpenRouter, Together, and
 * Anthropic's compatibility endpoint — so one code path covers local and cloud
 * setups alike. The user supplies the base URL, so nothing is hardcoded to a
 * particular vendor.
 *
 * Deliberately built on {@link HttpURLConnection} rather than the Rust core: TLS
 * then comes from the platform trust store and no new native dependency has to
 * be cross-compiled for the NDK.
 *
 * <p><b>Keep this class free of Android imports.</b> That is what lets the whole
 * request/response path — URL building, prompt templating, response parsing,
 * error mapping — run under a plain JVM against a local stub server, with no
 * emulator, no SDK and no {@code android.jar} stubs. {@link PostProcessor} is
 * the thin Android-facing wrapper over it.
 *
 * All methods block; callers are responsible for threading.
 */
public final class PostProcessClient {

    static final int CONNECT_TIMEOUT_MS = 10_000;
    static final int READ_TIMEOUT_MS = 45_000;
    /** Guards against a runaway model filling the text field. */
    static final int MAX_RESPONSE_BYTES = 256 * 1024;
    static final int MAX_OUTPUT_TOKENS = 2048;

    /** Placeholder replaced with the raw transcription, as in Handy. */
    static final String PLACEHOLDER = "${output}";

    /**
     * Used when the user hasn't written their own prompt. {@link #PLACEHOLDER}
     * is replaced with the raw transcription; see {@link #buildBody} for the
     * substitution rules when the placeholder is absent.
     */
    public static final String DEFAULT_PROMPT =
            "You are a dictation cleanup tool. Fix grammar, punctuation, capitalization "
            + "and obvious speech-to-text errors in the text below. Keep the original "
            + "meaning, tone and language — do not translate, summarize, answer, or add "
            + "anything. Reply with the corrected text only, with no preamble, quotes or "
            + "commentary.\n\n${output}";

    /**
     * Reasoning models (DeepSeek-R1 and friends served via Ollama) emit their
     * chain of thought inline in the message content. Typing that into the
     * user's text field would be worse than not post-processing at all.
     */
    private static final Pattern THINK_BLOCK = Pattern.compile(
            "(?is)<(think|thinking|reasoning)>.*?</\\1>");

    /** Thrown with a message meant to be shown to the user as-is. */
    public static class PostProcessException extends Exception {
        public PostProcessException(String message) { super(message); }
        public PostProcessException(String message, Throwable cause) { super(message, cause); }
    }

    private PostProcessClient() {}

    /**
     * Blocking round-trip to the configured endpoint.
     *
     * @throws PostProcessException with a message suitable for direct display.
     */
    public static String process(String baseUrl, String apiKey, String model,
                                 String promptTemplate, String transcript)
            throws PostProcessException {
        return process(baseUrl, apiKey, model, promptTemplate, transcript,
                CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
    }

    /**
     * Overload with explicit timeouts. Exists so the tests can exercise the
     * timeout path in milliseconds instead of waiting out the real 45 s.
     */
    static String process(String baseUrl, String apiKey, String model,
                          String promptTemplate, String transcript,
                          int connectTimeoutMs, int readTimeoutMs)
            throws PostProcessException {

        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            throw new PostProcessException("no server URL configured");
        }
        if (model == null || model.trim().isEmpty()) {
            throw new PostProcessException("no model configured");
        }

        URL url;
        try {
            url = new URL(endpointFor(baseUrl));
        } catch (MalformedURLException e) {
            throw new PostProcessException("invalid server URL");
        }

        byte[] body = buildBody(model, promptTemplate, transcript);

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            conn.setDoOutput(true);
            // Do NOT switch on setFixedLengthStreamingMode here. On a 401 —
            // by far the most common failure, a wrong API key — HttpURLConnection
            // starts its internal auth-retry, finds the request body can't be
            // replayed in streaming mode, gives up, and leaves getErrorStream()
            // returning null. The provider's explanation ("expired key",
            // "insufficient quota") is then lost and the user only sees the
            // generic message. Every other status is unaffected, which is what
            // makes this easy to reintroduce by accident; PostProcessClientTest
            // pins the behaviour. Buffering costs nothing at these sizes —
            // a prompt plus one dictated sentence.
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");
            if (apiKey != null && !apiKey.trim().isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
            }

            try (OutputStream out = conn.getOutputStream()) {
                out.write(body);
            }

            int status = conn.getResponseCode();
            if (status < 200 || status > 299) {
                throw new PostProcessException(describeHttpError(status,
                        readAll(conn.getErrorStream())));
            }

            String text = extractContent(readAll(conn.getInputStream()));
            if (text.isEmpty()) {
                throw new PostProcessException("the model returned an empty response");
            }
            return text;

        } catch (SocketTimeoutException e) {
            throw new PostProcessException("the server took too long to respond");
        } catch (UnknownHostException e) {
            throw new PostProcessException("can't reach the server (check the URL / network)");
        } catch (IOException e) {
            String detail = e.getMessage();
            throw new PostProcessException(
                    detail == null || detail.isEmpty() ? "network error" : detail, e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Turns a user-supplied base URL into the chat-completions endpoint.
     * Accepts a bare base ("http://host:11434/v1"), a base with a trailing
     * slash, or the full endpoint already spelled out.
     */
    static String endpointFor(String baseUrl) {
        String base = baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.endsWith("/chat/completions")) return base;
        return base + "/chat/completions";
    }

    /**
     * Builds the request. If the prompt template contains {@code ${output}} the
     * transcription is substituted into it and sent as a single user message —
     * that is the Handy convention and gives the user full control over framing.
     * A template without the placeholder is treated as a system instruction and
     * the transcription is sent as a separate user message, so a prompt like
     * "translate to German" also works as written.
     */
    static byte[] buildBody(String model, String promptTemplate, String transcript) {
        String template = (promptTemplate == null || promptTemplate.trim().isEmpty())
                ? DEFAULT_PROMPT
                : promptTemplate;

        JSONArray messages = new JSONArray();
        try {
            if (template.contains(PLACEHOLDER)) {
                // Literal replacement: the transcription must not be read as a
                // regex replacement (a stray "$1" would corrupt the text).
                String filled = template.replace(PLACEHOLDER, transcript);
                messages.put(message("user", filled));
            } else {
                messages.put(message("system", template));
                messages.put(message("user", transcript));
            }

            JSONObject root = new JSONObject();
            root.put("model", model.trim());
            root.put("messages", messages);
            root.put("stream", false);
            // Cleanup is a deterministic rewrite, not a creative task.
            root.put("temperature", 0);
            // Required by some OpenAI-compatible layers (Anthropic's, for one)
            // and a useful ceiling everywhere else: a rewrite of dictated text
            // is never long, so this only ever truncates a runaway response.
            root.put("max_tokens", MAX_OUTPUT_TOKENS);
            return root.toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException e) {
            // JSONObject.put only throws on NaN/Infinity values, none of which
            // occur here.
            throw new IllegalStateException("failed to build request body", e);
        }
    }

    private static JSONObject message(String role, String content) throws JSONException {
        JSONObject m = new JSONObject();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    /**
     * Pulls the assistant text out of a chat-completions response. Handles the
     * plain-string {@code content} every OpenAI-compatible server returns, plus
     * the content-parts array some (Anthropic's compat layer, a few gateways)
     * use instead.
     */
    static String extractContent(String json) throws PostProcessException {
        try {
            JSONObject root = new JSONObject(json);

            // Some servers answer 200 with an error object in the body.
            JSONObject err = root.optJSONObject("error");
            if (err != null) {
                String msg = err.optString("message", "");
                throw new PostProcessException(msg.isEmpty() ? "the server returned an error" : msg);
            }

            JSONArray choices = root.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                throw new PostProcessException("unexpected response from the server");
            }
            JSONObject msg = choices.getJSONObject(0).optJSONObject("message");
            if (msg == null) {
                throw new PostProcessException("unexpected response from the server");
            }

            String content;
            Object raw = msg.opt("content");
            if (raw == null || raw == JSONObject.NULL) {
                // A JSON null would stringify to the literal "null" and get
                // typed into the user's text field.
                content = "";
            } else if (raw instanceof JSONArray) {
                StringBuilder sb = new StringBuilder();
                JSONArray parts = (JSONArray) raw;
                for (int i = 0; i < parts.length(); i++) {
                    JSONObject part = parts.optJSONObject(i);
                    if (part != null) sb.append(part.optString("text", ""));
                }
                content = sb.toString();
            } else {
                content = raw.toString();
            }

            return cleanup(content);
        } catch (JSONException e) {
            throw new PostProcessException("couldn't read the server's response");
        }
    }

    /**
     * Strips reasoning blocks and surrounding whitespace, then unwraps the
     * quotes models like to add when a prompt says "reply with the text only".
     *
     * The unwrap only fires when the quotes enclose the whole string and none of
     * the same kind appear inside it, so dictation that legitimately contains
     * quoted speech is left alone.
     */
    static String cleanup(String content) {
        Matcher m = THINK_BLOCK.matcher(content);
        String out = m.replaceAll("").trim();
        out = unwrap(out, '"', '"');
        out = unwrap(out, '“', '”'); // “ ”
        return out;
    }

    private static String unwrap(String s, char open, char close) {
        if (s.length() < 2 || s.charAt(0) != open || s.charAt(s.length() - 1) != close) {
            return s;
        }
        String inner = s.substring(1, s.length() - 1);
        if (inner.indexOf(open) >= 0 || inner.indexOf(close) >= 0) {
            return s;
        }
        return inner.trim();
    }

    /** Maps an HTTP status to something a user can act on. */
    static String describeHttpError(int status, String body) {
        String detail = "";
        if (body != null && !body.isEmpty()) {
            try {
                JSONObject err = new JSONObject(body).optJSONObject("error");
                if (err != null) detail = err.optString("message", "");
            } catch (JSONException ignored) {
                // Not JSON (an HTML error page from a proxy, say) — ignore it
                // rather than typing markup at the user.
            }
        }

        String base;
        switch (status) {
            case 401:
            case 403:
                base = "the API key was rejected";
                break;
            case 404:
                base = "endpoint or model not found (check the URL and model name)";
                break;
            case 429:
                base = "rate limited by the provider";
                break;
            default:
                base = status >= 500
                        ? "the server had an error (HTTP " + status + ")"
                        : "HTTP " + status;
                break;
        }
        return detail.isEmpty() ? base : base + ": " + detail;
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int read;
            int total = 0;
            while ((read = in.read(buf)) != -1) {
                total += read;
                if (total > MAX_RESPONSE_BYTES) {
                    throw new IOException("response too large");
                }
                out.write(buf, 0, read);
            }
            return out.toString("UTF-8");
        } finally {
            in.close();
        }
    }
}
