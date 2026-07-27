package dev.notune.transcribe;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the post-processing request path.
 *
 * These run on a plain JVM — {@link PostProcessClient} has no Android imports —
 * against {@link StubServer} on loopback. No emulator, no SDK, no network.
 */
@DisplayName("PostProcessClient")
class PostProcessClientTest {

    private static final String MODEL = "test-model";
    private static final String TRANSCRIPT = "hello there how are you";

    // ---------------------------------------------------------------- URLs

    @Nested
    @DisplayName("endpointFor")
    class EndpointFor {

        @Test
        @DisplayName("appends the chat-completions path to a bare base")
        void bareBase() {
            assertEquals("http://host:11434/v1/chat/completions",
                    PostProcessClient.endpointFor("http://host:11434/v1"));
        }

        @Test
        @DisplayName("tolerates trailing slashes (Anthropic documents its base with one)")
        void trailingSlashes() {
            assertAll(
                    () -> assertEquals("https://api.anthropic.com/v1/chat/completions",
                            PostProcessClient.endpointFor("https://api.anthropic.com/v1/")),
                    () -> assertEquals("https://api.anthropic.com/v1/chat/completions",
                            PostProcessClient.endpointFor("https://api.anthropic.com/v1///")));
        }

        @Test
        @DisplayName("leaves a full endpoint alone instead of doubling the path")
        void alreadyFullEndpoint() {
            assertEquals("https://api.openai.com/v1/chat/completions",
                    PostProcessClient.endpointFor("https://api.openai.com/v1/chat/completions"));
        }

        @Test
        @DisplayName("trims surrounding whitespace from a pasted URL")
        void trimsWhitespace() {
            assertEquals("http://h/v1/chat/completions",
                    PostProcessClient.endpointFor("  http://h/v1  "));
        }
    }

    // ------------------------------------------------------------ Request

    @Nested
    @DisplayName("buildBody")
    class BuildBody {

        private JSONObject body(String prompt, String transcript) {
            return new JSONObject(new String(
                    PostProcessClient.buildBody(MODEL, prompt, transcript),
                    StandardCharsets.UTF_8));
        }

        @Test
        @DisplayName("substitutes ${output} and sends one user message")
        void placeholderPath() {
            JSONObject root = body("Fix this: ${output}", TRANSCRIPT);
            JSONArray messages = root.getJSONArray("messages");
            assertAll(
                    () -> assertEquals(1, messages.length()),
                    () -> assertEquals("user", messages.getJSONObject(0).getString("role")),
                    () -> assertEquals("Fix this: " + TRANSCRIPT,
                            messages.getJSONObject(0).getString("content")));
        }

        @Test
        @DisplayName("without the placeholder, sends prompt as system + transcript as user")
        void systemPath() {
            JSONObject root = body("Translate to German.", TRANSCRIPT);
            JSONArray messages = root.getJSONArray("messages");
            assertAll(
                    () -> assertEquals(2, messages.length()),
                    () -> assertEquals("system", messages.getJSONObject(0).getString("role")),
                    () -> assertEquals("Translate to German.",
                            messages.getJSONObject(0).getString("content")),
                    () -> assertEquals("user", messages.getJSONObject(1).getString("role")),
                    () -> assertEquals(TRANSCRIPT,
                            messages.getJSONObject(1).getString("content")));
        }

        @Test
        @DisplayName("treats the transcript literally, not as a regex replacement")
        void transcriptIsLiteral() {
            // String.replaceAll would eat "$1" and mangle backslashes. This is
            // the regression guard for using String.replace instead.
            String tricky = "costs $1 and $0 \\ backslash";
            JSONObject root = body("Fix: ${output}", tricky);
            assertEquals("Fix: " + tricky,
                    root.getJSONArray("messages").getJSONObject(0).getString("content"));
        }

        @Test
        @DisplayName("falls back to the default prompt when none is set")
        void defaultPrompt() {
            for (String empty : new String[] {null, "", "   "}) {
                JSONObject root = body(empty, TRANSCRIPT);
                String content = root.getJSONArray("messages").getJSONObject(0)
                        .getString("content");
                assertTrue(content.endsWith(TRANSCRIPT),
                        "default prompt should end with the substituted transcript");
                assertTrue(content.startsWith("You are a dictation cleanup tool."),
                        "expected the default prompt, got: " + content);
            }
        }

        @Test
        @DisplayName("sets the fields servers require: model, stream, temperature, max_tokens")
        void requiredFields() {
            JSONObject root = body("x ${output}", TRANSCRIPT);
            assertAll(
                    () -> assertEquals(MODEL, root.getString("model")),
                    () -> assertFalse(root.getBoolean("stream")),
                    () -> assertEquals(0, root.getInt("temperature")),
                    // Anthropic's OpenAI-compat layer rejects a request without it.
                    () -> assertEquals(PostProcessClient.MAX_OUTPUT_TOKENS,
                            root.getInt("max_tokens")));
        }

        @Test
        @DisplayName("trims a model name with stray whitespace")
        void trimsModel() {
            JSONObject root = new JSONObject(new String(
                    PostProcessClient.buildBody("  m  ", "p ${output}", TRANSCRIPT),
                    StandardCharsets.UTF_8));
            assertEquals("m", root.getString("model"));
        }
    }

    // ----------------------------------------------------------- Response

    @Nested
    @DisplayName("extractContent")
    class ExtractContent {

        @Test
        @DisplayName("reads a plain string content")
        void plainString() throws Exception {
            assertEquals("Hello there, how are you?",
                    PostProcessClient.extractContent(envelope("Hello there, how are you?")));
        }

        @Test
        @DisplayName("concatenates a content-parts array (Anthropic compat, some gateways)")
        void contentPartsArray() throws Exception {
            String json = "{\"choices\":[{\"message\":{\"content\":["
                    + "{\"type\":\"text\",\"text\":\"Hello \"},"
                    + "{\"type\":\"text\",\"text\":\"world\"}]}}]}";
            assertEquals("Hello world", PostProcessClient.extractContent(json));
        }

        @Test
        @DisplayName("returns empty for a JSON null instead of the literal \"null\"")
        void jsonNullContent() throws Exception {
            String json = "{\"choices\":[{\"message\":{\"content\":null}}]}";
            assertEquals("", PostProcessClient.extractContent(json));
        }

        @Test
        @DisplayName("strips <think> blocks so reasoning never reaches the text field")
        void stripsThinkBlocks() throws Exception {
            assertAll(
                    () -> assertEquals("Fixed text.", PostProcessClient.extractContent(
                            envelope("<think>The user said... let me fix it.</think>\\nFixed text."))),
                    () -> assertEquals("Fixed text.", PostProcessClient.extractContent(
                            envelope("<thinking>hmm</thinking> Fixed text."))),
                    () -> assertEquals("Fixed text.", PostProcessClient.extractContent(
                            envelope("<REASONING>x</REASONING>Fixed text."))));
        }

        @Test
        @DisplayName("unwraps quotes the model added around the whole answer")
        void unwrapsOuterQuotes() throws Exception {
            assertAll(
                    () -> assertEquals("Fixed text.",
                            PostProcessClient.extractContent(envelope("\\\"Fixed text.\\\""))),
                    () -> assertEquals("Fixed text.",
                            PostProcessClient.extractContent(envelope("“Fixed text.”"))));
        }

        @Test
        @DisplayName("keeps quotes that are part of the dictation")
        void keepsInteriorQuotes() throws Exception {
            // Unwrapping here would silently corrupt the user's text.
            String quoted = "He said \\\"hello\\\" and left";
            assertEquals("He said \"hello\" and left",
                    PostProcessClient.extractContent(envelope(quoted)));

            String bothQuoted = "\\\"a\\\" and \\\"b\\\"";
            assertEquals("\"a\" and \"b\"",
                    PostProcessClient.extractContent(envelope(bothQuoted)));
        }

        @Test
        @DisplayName("surfaces an error object returned with HTTP 200")
        void errorObjectInBody() {
            String json = "{\"error\":{\"message\":\"model not loaded\",\"type\":\"invalid\"}}";
            PostProcessClient.PostProcessException e = assertThrows(
                    PostProcessClient.PostProcessException.class,
                    () -> PostProcessClient.extractContent(json));
            assertEquals("model not loaded", e.getMessage());
        }

        @Test
        @DisplayName("reports unreadable and unexpected payloads distinctly")
        void malformedPayloads() {
            assertAll(
                    () -> assertEquals("couldn't read the server's response",
                            assertThrows(PostProcessClient.PostProcessException.class,
                                    () -> PostProcessClient.extractContent("<html>502</html>"))
                                    .getMessage()),
                    () -> assertEquals("unexpected response from the server",
                            assertThrows(PostProcessClient.PostProcessException.class,
                                    () -> PostProcessClient.extractContent("{\"choices\":[]}"))
                                    .getMessage()),
                    () -> assertEquals("unexpected response from the server",
                            assertThrows(PostProcessClient.PostProcessException.class,
                                    () -> PostProcessClient.extractContent("{\"choices\":[{}]}"))
                                    .getMessage()));
        }

        private String envelope(String escapedContent) {
            return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\""
                    + escapedContent + "\"}}]}";
        }
    }

    // ------------------------------------------------------- Error mapping

    @Nested
    @DisplayName("describeHttpError")
    class DescribeHttpError {

        @Test
        @DisplayName("maps the statuses a user can act on")
        void actionableStatuses() {
            assertAll(
                    () -> assertEquals("the API key was rejected",
                            PostProcessClient.describeHttpError(401, "")),
                    () -> assertEquals("the API key was rejected",
                            PostProcessClient.describeHttpError(403, "")),
                    () -> assertEquals("endpoint or model not found "
                                    + "(check the URL and model name)",
                            PostProcessClient.describeHttpError(404, "")),
                    () -> assertEquals("rate limited by the provider",
                            PostProcessClient.describeHttpError(429, "")),
                    () -> assertEquals("the server had an error (HTTP 503)",
                            PostProcessClient.describeHttpError(503, "")),
                    () -> assertEquals("HTTP 418",
                            PostProcessClient.describeHttpError(418, "")));
        }

        @Test
        @DisplayName("appends the provider's own message when there is one")
        void appendsProviderDetail() {
            assertEquals("the API key was rejected: Incorrect API key provided",
                    PostProcessClient.describeHttpError(401,
                            "{\"error\":{\"message\":\"Incorrect API key provided\"}}"));
        }

        @Test
        @DisplayName("ignores a non-JSON error page rather than showing markup")
        void ignoresHtmlErrorPage() {
            assertEquals("the server had an error (HTTP 502)",
                    PostProcessClient.describeHttpError(502, "<html><body>Bad Gateway</body></html>"));
        }
    }

    // ------------------------------------------------- End-to-end over HTTP

    @Nested
    @DisplayName("process (over HTTP)")
    class Process {

        @Test
        @DisplayName("round-trips and sends a well-formed authenticated request")
        void happyPath() throws Exception {
            try (StubServer server = new StubServer(
                    (exchange, captured) -> StubServer.replyContent(exchange,
                            "Hello there, how are you?"))) {

                String out = PostProcessClient.process(server.baseUrl(), "sk-test-key",
                        MODEL, "Fix: ${output}", TRANSCRIPT);
                assertEquals("Hello there, how are you?", out);

                StubServer.Captured req = server.lastRequest();
                assertAll(
                        () -> assertEquals("POST", req.method),
                        () -> assertEquals("Bearer sk-test-key", req.authorization),
                        () -> assertTrue(req.contentType.startsWith("application/json")),
                        () -> assertEquals("application/json", req.accept));

                JSONObject sent = new JSONObject(req.body);
                assertAll(
                        () -> assertEquals(MODEL, sent.getString("model")),
                        () -> assertEquals("Fix: " + TRANSCRIPT,
                                sent.getJSONArray("messages").getJSONObject(0)
                                        .getString("content")));
            }
        }

        @Test
        @DisplayName("omits the Authorization header when no key is set (local servers)")
        void noApiKeyMeansNoAuthHeader() throws Exception {
            try (StubServer server = new StubServer(
                    (exchange, captured) -> StubServer.replyContent(exchange, "ok"))) {

                for (String key : new String[] {null, "", "   "}) {
                    PostProcessClient.process(server.baseUrl(), key, MODEL, "p ${output}", "t");
                    assertNull(server.lastRequest().authorization,
                            "Ollama and LM Studio reject an empty bearer token");
                }
            }
        }

        @Test
        @DisplayName("maps HTTP failures to actionable messages")
        void httpFailures() throws Exception {
            assertAll(
                    () -> assertStatusMessage(401,
                            "{\"error\":{\"message\":\"bad key\"}}",
                            "the API key was rejected: bad key"),
                    () -> assertStatusMessage(404, "{}",
                            "endpoint or model not found (check the URL and model name)"),
                    () -> assertStatusMessage(429, "{}", "rate limited by the provider"),
                    () -> assertStatusMessage(500, "{}", "the server had an error (HTTP 500)"));
        }

        private void assertStatusMessage(int status, String body, String expected)
                throws Exception {
            try (StubServer server = new StubServer(
                    (exchange, captured) -> StubServer.replyRaw(exchange, status, body))) {
                PostProcessClient.PostProcessException e = assertThrows(
                        PostProcessClient.PostProcessException.class,
                        () -> PostProcessClient.process(server.baseUrl(), "k", MODEL,
                                "p ${output}", "t"));
                assertEquals(expected, e.getMessage());
            }
        }

        @Test
        @DisplayName("rejects an empty completion rather than inserting nothing")
        void emptyCompletion() throws Exception {
            try (StubServer server = new StubServer(
                    (exchange, captured) -> StubServer.replyContent(exchange, "   "))) {
                PostProcessClient.PostProcessException e = assertThrows(
                        PostProcessClient.PostProcessException.class,
                        () -> PostProcessClient.process(server.baseUrl(), "k", MODEL,
                                "p ${output}", "t"));
                assertEquals("the model returned an empty response", e.getMessage());
            }
        }

        @Test
        @DisplayName("reports a slow server as a timeout, not a generic failure")
        void readTimeout() throws Exception {
            try (StubServer server = new StubServer((exchange, captured) -> {
                try {
                    Thread.sleep(2_000); // outlives the 300 ms read timeout below
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                StubServer.replyContent(exchange, "too late");
            })) {
                PostProcessClient.PostProcessException e = assertThrows(
                        PostProcessClient.PostProcessException.class,
                        () -> PostProcessClient.process(server.baseUrl(), "k", MODEL,
                                "p ${output}", "t", 1_000, 300));
                assertEquals("the server took too long to respond", e.getMessage());
            }
        }

        @Test
        @DisplayName("refuses a response larger than the cap")
        void oversizedResponse() throws Exception {
            StringBuilder huge = new StringBuilder();
            while (huge.length() < PostProcessClient.MAX_RESPONSE_BYTES + 4096) {
                huge.append("aaaaaaaaaaaaaaaa");
            }
            try (StubServer server = new StubServer(
                    (exchange, captured) -> StubServer.replyContent(exchange, huge.toString()))) {
                PostProcessClient.PostProcessException e = assertThrows(
                        PostProcessClient.PostProcessException.class,
                        () -> PostProcessClient.process(server.baseUrl(), "k", MODEL,
                                "p ${output}", "t"));
                assertEquals("response too large", e.getMessage());
            }
        }

        @Test
        @DisplayName("reports an unresolvable host without leaking a stack trace")
        void unknownHost() {
            // .invalid is reserved by RFC 2606 and never resolves, so this is
            // deterministic with or without a network.
            PostProcessClient.PostProcessException e = assertThrows(
                    PostProcessClient.PostProcessException.class,
                    () -> PostProcessClient.process("http://nonexistent.invalid/v1", "k",
                            MODEL, "p ${output}", "t", 1_000, 1_000));
            assertNotNull(e.getMessage());
            assertFalse(e.getMessage().contains("Exception"),
                    "user-facing message should not contain a class name: " + e.getMessage());
        }

        @Test
        @DisplayName("validates configuration before opening a socket")
        void validatesConfig() {
            assertAll(
                    () -> assertEquals("no server URL configured",
                            assertThrows(PostProcessClient.PostProcessException.class,
                                    () -> PostProcessClient.process("", "k", MODEL, "p", "t"))
                                    .getMessage()),
                    () -> assertEquals("no model configured",
                            assertThrows(PostProcessClient.PostProcessException.class,
                                    () -> PostProcessClient.process("http://h/v1", "k", " ", "p", "t"))
                                    .getMessage()),
                    () -> assertEquals("invalid server URL",
                            assertThrows(PostProcessClient.PostProcessException.class,
                                    () -> PostProcessClient.process("not a url", "k", MODEL, "p", "t"))
                                    .getMessage()));
        }
    }
}
