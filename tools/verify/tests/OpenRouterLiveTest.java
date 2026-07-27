package dev.notune.transcribe;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Optional smoke test against the real OpenRouter API.
 *
 * Skipped unless {@code OPENROUTER_API_KEY} is set, so the default test run
 * stays offline and deterministic. Enable it with:
 *
 * <pre>
 *   export OPENROUTER_API_KEY=sk-or-v1-...
 *   tools/verify/run.sh tier1
 * </pre>
 *
 * <p>What this adds over {@link PostProcessClientTest}, which covers the same
 * paths against a local stub: a real provider's actual response envelope, its
 * real auth handling, and its real error bodies. The stub encodes what the
 * OpenAI-compatible spec <em>says</em>; this checks what a provider
 * <em>does</em>.
 *
 * <p>Costs a fraction of a cent per run — one small completion. The bad-key
 * case costs nothing, since it never reaches a model.
 *
 * <p>The key is read from the environment and never logged. Assertions
 * deliberately avoid echoing request headers.
 */
@DisplayName("OpenRouter (live)")
@EnabledIfEnvironmentVariable(named = "OPENROUTER_API_KEY", matches = ".+")
class OpenRouterLiveTest {

    private static final String BASE_URL = "https://openrouter.ai/api/v1";

    /** Overridable so a cheaper or differently-available model can be used. */
    private static String model() {
        String m = System.getenv("OPENROUTER_MODEL");
        return (m == null || m.trim().isEmpty()) ? "openai/gpt-4o-mini" : m.trim();
    }

    private static String key() {
        return System.getenv("OPENROUTER_API_KEY");
    }

    @Test
    @DisplayName("cleans up a real dictation through a real provider")
    void realRoundTrip() throws Exception {
        String transcript = "so i was thinking maybe we could meet on tuesday "
                + "afternoon instead does that work for you";

        String out = PostProcessClient.process(BASE_URL, key(), model(),
                PostProcessClient.DEFAULT_PROMPT, transcript);

        System.out.println("  model:  " + model());
        System.out.println("  sent:   " + transcript);
        System.out.println("  got:    " + out);

        assertNotNull(out);
        assertFalse(out.isEmpty(), "a real provider should return text");

        // Assert on properties the prompt actually asks for, not on an exact
        // string — the model's wording is not ours to pin down.
        assertTrue(out.length() > transcript.length() / 2,
                "cleanup should not gut the text: " + out);
        assertTrue(Character.isUpperCase(out.charAt(0)),
                "the prompt asks for capitalization to be fixed: " + out);
        assertFalse(out.contains("${output}"),
                "the placeholder must have been substituted, not passed through");
        assertFalse(out.toLowerCase().startsWith("here"),
                "the prompt forbids a preamble: " + out);
    }

    @Test
    @DisplayName("maps a rejected key to the message the keyboard shows")
    void badKeyIsReportedCleanly() {
        // This is the real-provider counterpart to the regression test for the
        // setFixedLengthStreamingMode bug: it proves a genuine 401 body still
        // reaches the user, not just a stubbed one.
        PostProcessClient.PostProcessException e = assertThrows(
                PostProcessClient.PostProcessException.class,
                () -> PostProcessClient.process(BASE_URL, "sk-or-v1-definitely-not-valid",
                        model(), PostProcessClient.DEFAULT_PROMPT, "hello"));

        System.out.println("  bad-key message: " + e.getMessage());

        assertTrue(e.getMessage().startsWith("the API key was rejected"),
                "expected the user-facing auth message, got: " + e.getMessage());
        assertFalse(e.getMessage().contains("Exception"),
                "user-facing message should not leak a class name");
    }

    @Test
    @DisplayName("reports an unknown model without leaking internals")
    void unknownModel() {
        PostProcessClient.PostProcessException e = assertThrows(
                PostProcessClient.PostProcessException.class,
                () -> PostProcessClient.process(BASE_URL, key(),
                        "no-such-vendor/no-such-model-12345",
                        PostProcessClient.DEFAULT_PROMPT, "hello"));

        System.out.println("  unknown-model message: " + e.getMessage());

        assertNotNull(e.getMessage());
        assertFalse(e.getMessage().isEmpty());
        assertFalse(e.getMessage().contains("Exception"),
                "user-facing message should not leak a class name");
    }

    @Test
    @DisplayName("a custom prompt instruction is followed end to end")
    void customPrompt() throws Exception {
        String out = PostProcessClient.process(BASE_URL, key(), model(),
                "Rewrite the following as a single bullet point starting with "
                        + "'- '. Reply with the bullet only.\n\n${output}",
                "we should ship the release on friday");

        System.out.println("  custom-prompt result: " + out);

        assertTrue(out.startsWith("-"),
                "the custom prompt asked for a leading bullet: " + out);
    }

    @Test
    @DisplayName("the endpoint URL we build is the one OpenRouter documents")
    void endpointShape() {
        assertEquals("https://openrouter.ai/api/v1/chat/completions",
                PostProcessClient.endpointFor(BASE_URL));
    }
}
