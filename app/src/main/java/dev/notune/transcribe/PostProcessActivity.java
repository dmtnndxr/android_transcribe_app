package dev.notune.transcribe;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Configures LLM post-processing: the endpoint, credentials, model and prompt
 * used by the voice keyboard's second mic.
 *
 * The endpoint is any OpenAI-compatible {@code /chat/completions} server, which
 * covers a local Ollama or LM Studio on the same network as well as the hosted
 * providers. Presets only prefill the URL and a plausible model name — every
 * field stays editable, and "Custom" leaves them alone.
 *
 * Values are written back in {@link #onPause()} (and before a test run), so the
 * screen has no save button; the IME picks changes up the next time it is shown.
 */
public class PostProcessActivity extends AppCompatActivity {

    /** Sample text for the Test button — deliberately messy, in one sentence. */
    private static final String TEST_TRANSCRIPT =
            "so i was thinking maybe we could meet on tuesday afternoon "
            + "instead does that work for you";

    /** A named endpoint + a plausible starting model. */
    private static final class Preset {
        final String label;
        final String baseUrl;
        final String model;

        Preset(String label, String baseUrl, String model) {
            this.label = label;
            this.baseUrl = baseUrl;
            this.model = model;
        }
    }

    private final List<Preset> presets = new ArrayList<>();

    private MaterialSwitch enabledSwitch;
    private ViewGroup configGroup;
    private Spinner presetSpinner;
    private TextInputEditText baseUrlEdit;
    private TextInputEditText apiKeyEdit;
    private TextInputEditText modelEdit;
    private TextInputEditText promptEdit;
    private TextView cleartextWarning;
    private TextView testResult;
    private MaterialButton testButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_post_process);

        // "Custom" first so it is the resting state for a hand-typed URL.
        presets.add(new Preset(getString(R.string.pp_preset_custom), null, null));
        presets.add(new Preset("Ollama (local)", "http://localhost:11434/v1", "llama3.2"));
        presets.add(new Preset("LM Studio (local)", "http://localhost:1234/v1", ""));
        presets.add(new Preset("llama.cpp server (local)", "http://localhost:8080/v1", ""));
        presets.add(new Preset("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"));
        presets.add(new Preset("Groq", "https://api.groq.com/openai/v1",
                "llama-3.3-70b-versatile"));
        presets.add(new Preset("OpenRouter", "https://openrouter.ai/api/v1",
                "openai/gpt-4o-mini"));
        presets.add(new Preset("Anthropic", "https://api.anthropic.com/v1", "claude-opus-5"));

        enabledSwitch = findViewById(R.id.switch_pp_enabled);
        configGroup = findViewById(R.id.pp_config_group);
        presetSpinner = findViewById(R.id.spinner_pp_preset);
        baseUrlEdit = findViewById(R.id.edit_pp_base_url);
        apiKeyEdit = findViewById(R.id.edit_pp_api_key);
        modelEdit = findViewById(R.id.edit_pp_model);
        promptEdit = findViewById(R.id.edit_pp_prompt);
        cleartextWarning = findViewById(R.id.text_pp_cleartext_warning);
        testResult = findViewById(R.id.text_pp_test_result);
        testButton = findViewById(R.id.btn_pp_test);

        baseUrlEdit.setText(PostProcessPrefs.getBaseUrl(this));
        apiKeyEdit.setText(PostProcessPrefs.getApiKey(this));
        modelEdit.setText(PostProcessPrefs.getModel(this));
        promptEdit.setText(PostProcessPrefs.getPrompt(this));

        enabledSwitch.setChecked(PostProcessPrefs.isEnabled(this));
        enabledSwitch.setOnCheckedChangeListener((v, checked) -> {
            PostProcessPrefs.setEnabled(this, checked);
            applyEnabledState(checked);
        });
        applyEnabledState(enabledSwitch.isChecked());

        setupPresetSpinner();

        baseUrlEdit.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                updateCleartextWarning(s.toString());
            }
        });
        updateCleartextWarning(baseUrlEdit.getText() == null
                ? "" : baseUrlEdit.getText().toString());

        findViewById(R.id.btn_pp_reset_prompt).setOnClickListener(v -> {
            promptEdit.setText(PostProcessPrefs.DEFAULT_PROMPT);
            snackbar(getString(R.string.pp_prompt_reset));
        });

        testButton.setOnClickListener(v -> runTest());
    }

    @Override
    protected void onPause() {
        super.onPause();
        save();
    }

    private void save() {
        PostProcessPrefs.setBaseUrl(this, text(baseUrlEdit));
        PostProcessPrefs.setApiKey(this, text(apiKeyEdit));
        PostProcessPrefs.setModel(this, text(modelEdit));

        // Storing the default verbatim would freeze it: a later change to
        // DEFAULT_PROMPT wouldn't reach users who never edited theirs. Clearing
        // it instead keeps them on whatever the current default is.
        String prompt = text(promptEdit);
        PostProcessPrefs.setPrompt(this,
                prompt.equals(PostProcessPrefs.DEFAULT_PROMPT) ? "" : prompt);
    }

    private static String text(TextInputEditText edit) {
        return edit.getText() == null ? "" : edit.getText().toString().trim();
    }

    private void setupPresetSpinner() {
        List<String> labels = new ArrayList<>(presets.size());
        for (Preset p : presets) {
            labels.add(p.label);
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        presetSpinner.setAdapter(adapter);
        presetSpinner.setSelection(indexOfCurrentPreset(), false);

        presetSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                Preset p = presets.get(position);
                if (p.baseUrl == null) return; // Custom: leave the fields alone.
                // No-op if the field already matches. This is what absorbs the
                // callback Android fires for the initial selection, without the
                // "first real choice gets swallowed" bug a one-shot flag has.
                if (p.baseUrl.equalsIgnoreCase(text(baseUrlEdit))) return;
                baseUrlEdit.setText(p.baseUrl);
                // Only prefill the model when the preset has a suggestion and
                // the user hasn't typed one, so switching presets to fix a URL
                // doesn't silently discard a deliberate model choice.
                if (!p.model.isEmpty() && text(modelEdit).isEmpty()) {
                    modelEdit.setText(p.model);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    /** Matches the stored base URL back to a preset, or "Custom" if none fits. */
    private int indexOfCurrentPreset() {
        String current = PostProcessPrefs.getBaseUrl(this);
        if (!current.isEmpty()) {
            for (int i = 0; i < presets.size(); i++) {
                String base = presets.get(i).baseUrl;
                if (base != null && base.equalsIgnoreCase(current)) return i;
            }
        }
        return 0; // Custom
    }

    /**
     * Flags a plaintext endpoint. Loopback is exempt: traffic to the device
     * itself never leaves it, so there is nothing to intercept.
     */
    private void updateCleartextWarning(String url) {
        String lower = url.trim().toLowerCase(Locale.US);
        boolean cleartext = lower.startsWith("http://")
                && !lower.startsWith("http://localhost")
                && !lower.startsWith("http://127.0.0.1")
                && !lower.startsWith("http://[::1]");
        cleartextWarning.setVisibility(cleartext ? View.VISIBLE : View.GONE);
    }

    private void applyEnabledState(boolean enabled) {
        setGroupEnabled(configGroup, enabled);
        configGroup.setAlpha(enabled ? 1.0f : 0.5f);
    }

    /** Enables/disables a whole subtree — Android does not cascade this. */
    private void setGroupEnabled(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                setGroupEnabled(group.getChildAt(i), enabled);
            }
        }
    }

    /**
     * Round-trips a sample sentence so the user can confirm the endpoint,
     * credentials, model and prompt all work before relying on them mid-typing.
     */
    private void runTest() {
        save();

        if (PostProcessPrefs.getBaseUrl(this).isEmpty()
                || PostProcessPrefs.getModel(this).isEmpty()) {
            showTestResult(getString(R.string.pp_test_incomplete), true);
            return;
        }

        testButton.setEnabled(false);
        showTestResult(getString(R.string.pp_test_running), false);

        final String baseUrl = PostProcessPrefs.getBaseUrl(this);
        final String apiKey = PostProcessPrefs.getApiKey(this);
        final String model = PostProcessPrefs.getModel(this);
        final String prompt = PostProcessPrefs.getPrompt(this);

        new Thread(() -> {
            String message;
            boolean isError;
            try {
                String out = PostProcessClient.process(
                        baseUrl, apiKey, model, prompt, TEST_TRANSCRIPT);
                message = getString(R.string.pp_test_ok, TEST_TRANSCRIPT, out);
                isError = false;
            } catch (PostProcessClient.PostProcessException e) {
                message = getString(R.string.pp_test_failed, e.getMessage());
                isError = true;
            }
            final String finalMessage = message;
            final boolean finalIsError = isError;
            runOnUiThread(() -> {
                testButton.setEnabled(true);
                showTestResult(finalMessage, finalIsError);
            });
        }, "post-process-test").start();
    }

    private void showTestResult(String message, boolean isError) {
        testResult.setVisibility(View.VISIBLE);
        testResult.setText(message);
        testResult.setTextColor(isError
                ? com.google.android.material.color.MaterialColors.getColor(
                        testResult, com.google.android.material.R.attr.colorError)
                : com.google.android.material.color.MaterialColors.getColor(
                        testResult, com.google.android.material.R.attr.colorOnSurface));
    }

    private void snackbar(String message) {
        Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_LONG).show();
    }
}
