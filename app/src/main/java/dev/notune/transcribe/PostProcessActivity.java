package dev.notune.transcribe;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.format.Formatter;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.PopupMenu;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.mlkit.genai.common.DownloadCallback;
import com.google.mlkit.genai.common.FeatureStatus;
import com.google.mlkit.genai.common.GenAiException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;

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
    private AutoCompleteTextView modelEdit;
    private TextView modelsStatus;
    private MaterialButton loadModelsButton;
    /** Base URL the model list was last loaded for, so it isn't refetched needlessly. */
    private String modelsLoadedFor;
    private TextInputEditText promptEdit;
    private TextView cleartextWarning;
    private TextView testResult;
    private MaterialButton testButton;
    private PostProcessPrefs.Provider provider;
    private View serverGroup;
    private View serverPrivacy;
    private View nanoGroup;
    private TextView promptDescription;
    private TextView testDescription;
    private TextView nanoStatus;
    private MaterialButton nanoDownload;
    private MaterialButton nanoRefresh;
    private Future<?> nanoTask;
    private Future<?> testTask;
    private int nanoStatusGeneration;
    private int testGeneration;
    private int nanoFeature = FeatureStatus.UNAVAILABLE;
    private boolean resumed;
    private final Runnable refreshNano = () -> runNanoStatus(false);

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
        serverGroup = findViewById(R.id.pp_server_group);
        serverPrivacy = findViewById(R.id.text_pp_server_privacy);
        nanoGroup = findViewById(R.id.pp_nano_group);
        promptDescription = findViewById(R.id.text_pp_prompt_desc);
        testDescription = findViewById(R.id.text_pp_test_desc);
        nanoStatus = findViewById(R.id.text_pp_nano_status);
        nanoDownload = findViewById(R.id.btn_pp_nano_download);
        nanoRefresh = findViewById(R.id.btn_pp_nano_refresh);

        provider = PostProcessPrefs.getProvider(this);
        RadioGroup providers = findViewById(R.id.pp_provider_group);
        providers.check(usesNano() ? R.id.pp_provider_nano : R.id.pp_provider_server);
        providers.setOnCheckedChangeListener((group, checked) -> {
            if (checked == View.NO_ID) return;
            PostProcessPrefs.Provider selected = checked == R.id.pp_provider_nano
                    ? PostProcessPrefs.Provider.GEMINI_NANO
                    : PostProcessPrefs.Provider.OPENAI_COMPATIBLE;
            if (selected == provider) return;
            if (!PostProcessPrefs.setProvider(this, selected)) {
                group.check(usesNano() ? R.id.pp_provider_nano : R.id.pp_provider_server);
                snackbar(getString(R.string.pp_provider_save_failed));
                return;
            }
            if (!usesNano()) saveServer();
            provider = selected;
            clearTest();
            updateProviderUi();
            runNanoStatus(false);
        });
        nanoRefresh.setOnClickListener(v -> runNanoStatus(false));
        nanoDownload.setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.pp_nano_download)
                .setMessage(R.string.pp_nano_download_consent)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.pp_nano_download, (dialog, which) -> runNanoStatus(true))
                .show());

        baseUrlEdit.setText(PostProcessPrefs.getBaseUrl(this));
        apiKeyEdit.setText(PostProcessPrefs.getApiKey(this));
        modelEdit.setText(PostProcessPrefs.getModel(this));
        promptEdit.setText(PostProcessPrefs.getPrompt(this));

        enabledSwitch.setChecked(PostProcessPrefs.isEnabled(this));
        enabledSwitch.setOnCheckedChangeListener((v, checked) -> {
            PostProcessPrefs.setEnabled(this, checked);
            applyEnabledState(checked);
            if (checked) runNanoStatus(false);
            else clearTest();
        });

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

        modelsStatus = findViewById(R.id.text_pp_models_status);
        loadModelsButton = findViewById(R.id.btn_pp_load_models);
        loadModelsButton.setOnClickListener(v -> loadModels(true));
        modelEdit.setOnFocusChangeListener((v, focused) -> {
            if (focused) loadModels(false);
        });
        showSuggestedModels();

        findViewById(R.id.btn_pp_templates).setOnClickListener(v -> {
            String[][] templates = PromptTemplates.all(this);
            PopupMenu menu = new PopupMenu(this, v);
            for (int i = 0; i < templates.length; i++) menu.getMenu().add(0, i, i, templates[i][0]);
            menu.setOnMenuItemClickListener(item -> {
                promptEdit.setText(templates[item.getItemId()][1]);
                snackbar(getString(R.string.pp_template_applied, templates[item.getItemId()][0]));
                return true;
            });
            menu.show();
        });
        applyEnabledState(enabledSwitch.isChecked());
    }

    /** Before the server's list arrives, offer the presets' suggestions. */
    private void showSuggestedModels() {
        List<String> names = new ArrayList<>();
        for (Preset p : presets) {
            if (p.model != null && !p.model.isEmpty() && !names.contains(p.model)) names.add(p.model);
        }
        setModelChoices(names);
    }

    private void setModelChoices(List<String> names) {
        modelEdit.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_dropdown_item_1line, names));
    }

    /**
     * Fetches the server's model list. Runs by itself the first time the model
     * field gets focus; {@code force} (the button) also retries after an error.
     */
    private void loadModels(boolean force) {
        if (usesNano() || !enabledSwitch.isChecked()) return;
        String baseUrl = text(baseUrlEdit);
        if (baseUrl.isEmpty()) {
            if (force) modelsStatus.setText(R.string.pp_models_need_url);
            return;
        }
        if (!force && baseUrl.equals(modelsLoadedFor)) return;
        modelsLoadedFor = baseUrl;
        String apiKey = text(apiKeyEdit);
        loadModelsButton.setEnabled(false);
        modelsStatus.setText(R.string.pp_models_loading);
        new Thread(() -> {
            List<String> ids = null;
            String error = null;
            try {
                ids = PostProcessClient.listModels(baseUrl, apiKey);
            } catch (PostProcessClient.PostProcessException e) {
                error = e.getMessage();
            }
            final List<String> finalIds = ids;
            final String finalError = error;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                loadModelsButton.setEnabled(true);
                if (finalIds == null) {
                    modelsLoadedFor = null;
                    modelsStatus.setText(getString(R.string.pp_models_failed, finalError));
                } else {
                    setModelChoices(finalIds);
                    modelsStatus.setText(getResources().getQuantityString(
                            R.plurals.pp_models_loaded, finalIds.size(), finalIds.size()));
                    if (!usesNano() && modelEdit.hasFocus()) modelEdit.showDropDown();
                }
            });
        }, "list-models").start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        runNanoStatus(false);
    }

    @Override
    protected void onPause() {
        resumed = false;
        nanoStatus.removeCallbacks(refreshNano);
        super.onPause();
        save();
    }

    @Override
    protected void onDestroy() {
        nanoStatusGeneration++;
        if (nanoTask != null) nanoTask.cancel(true);
        clearTest();
        super.onDestroy();
    }

    private void save() {
        if (!usesNano()) saveServer();

        // Storing the default verbatim would freeze it: a later change to
        // DEFAULT_PROMPT wouldn't reach users who never edited theirs. Clearing
        // it instead keeps them on whatever the current default is.
        String prompt = text(promptEdit);
        PostProcessPrefs.setPrompt(this,
                prompt.equals(PostProcessPrefs.DEFAULT_PROMPT) ? "" : prompt);
    }

    private void saveServer() {
        PostProcessPrefs.setBaseUrl(this, text(baseUrlEdit));
        PostProcessPrefs.setApiKey(this, text(apiKeyEdit));
        PostProcessPrefs.setModel(this, text(modelEdit));
    }

    private static String text(TextView edit) {
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
        updateProviderUi();
    }

    private boolean usesNano() {
        return provider == PostProcessPrefs.Provider.GEMINI_NANO;
    }

    private void updateProviderUi() {
        boolean nano = usesNano();
        serverGroup.setVisibility(nano ? View.GONE : View.VISIBLE);
        serverPrivacy.setVisibility(nano ? View.GONE : View.VISIBLE);
        nanoGroup.setVisibility(nano ? View.VISIBLE : View.GONE);
        promptDescription.setText(nano ? R.string.pp_nano_prompt_desc : R.string.pp_prompt_desc);
        testDescription.setText(nano ? R.string.pp_nano_test_desc : R.string.pp_test_desc);
        testButton.setText(nano ? R.string.pp_nano_test : R.string.pp_test);
        updateActionButtons();
    }

    private void updateActionButtons() {
        boolean enabled = enabledSwitch.isChecked();
        nanoRefresh.setEnabled(enabled && nanoTask == null);
        nanoDownload.setVisibility(nanoFeature == FeatureStatus.DOWNLOADABLE
                && nanoTask == null ? View.VISIBLE : View.GONE);
        nanoDownload.setEnabled(enabled);
        testButton.setEnabled(enabled && testTask == null && (!usesNano()
                || (nanoTask == null && nanoFeature == FeatureStatus.AVAILABLE)));
    }

    private void runNanoStatus(boolean download) {
        if (!resumed || !enabledSwitch.isChecked() || !usesNano() || nanoTask != null) return;
        nanoStatus.removeCallbacks(refreshNano);
        final int generation = ++nanoStatusGeneration;
        nanoStatus.setText(download ? R.string.pp_nano_downloading : R.string.pp_nano_checking);
        FutureTask<Void> task = new FutureTask<>(() -> {
            int status = FeatureStatus.UNAVAILABLE;
            int error = 0;
            try (GeminiNano nano = new GeminiNano(getApplicationContext())) {
                if (download) {
                    AtomicReference<GenAiException> downloadError = new AtomicReference<>();
                    nano.download(new DownloadCallback() {
                        @Override public void onDownloadProgress(long bytes) {
                            runOnUiThread(() -> {
                                if (generation == nanoStatusGeneration && nanoTask != null) {
                                    nanoStatus.setText(getString(R.string.pp_nano_download_progress,
                                            Formatter.formatShortFileSize(PostProcessActivity.this, bytes)));
                                }
                            });
                        }

                        @Override public void onDownloadFailed(GenAiException failure) {
                            downloadError.set(failure);
                        }
                    });
                    if (downloadError.get() != null) error = GeminiNano.errorMessage(downloadError.get());
                }
                status = nano.checkStatus();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception | LinkageError e) {
                error = GeminiNano.errorMessage(e);
            }
            final int result = status;
            final int failure = error;
            runOnUiThread(() -> finishNanoStatus(generation, download, result, failure));
        }, null);
        nanoTask = task;
        new Thread(task, "gemini-nano-status").start();
        updateActionButtons();
    }

    private void finishNanoStatus(int generation, boolean download, int status, int error) {
        if (generation != nanoStatusGeneration || isFinishing()) return;
        nanoTask = null;
        if (error == 0) {
            nanoFeature = status;
            switch (status) {
                case FeatureStatus.AVAILABLE: nanoStatus.setText(R.string.pp_nano_ready); break;
                case FeatureStatus.DOWNLOADABLE:
                    nanoStatus.setText(R.string.pp_nano_downloadable);
                    break;
                case FeatureStatus.DOWNLOADING:
                    nanoStatus.setText(R.string.pp_nano_downloading);
                    if (resumed) nanoStatus.postDelayed(refreshNano, 4000);
                    break;
                default: nanoStatus.setText(R.string.pp_nano_unavailable);
            }
        } else {
            nanoFeature = download ? FeatureStatus.DOWNLOADABLE : FeatureStatus.UNAVAILABLE;
            nanoStatus.setText(download ? getString(R.string.pp_nano_download_failed, getString(error))
                    : getString(error));
        }
        updateActionButtons();
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

        if (!PostProcessPrefs.isConfigured(this)) {
            showTestResult(getString(R.string.pp_test_incomplete), true);
            return;
        }

        int generation = ++testGeneration;
        boolean nano = usesNano();
        String sample = nano ? getString(R.string.onb_ai_sample) : TEST_TRANSCRIPT;
        showTestResult(getString(nano ? R.string.ime_ai_working : R.string.pp_test_running), false);
        testTask = PostProcessor.processAsync(this, provider, sample,
                new PostProcessor.Callback() {
            @Override public void onSuccess(String text) {
                runOnUiThread(() -> finishTest(generation,
                        getString(nano ? R.string.pp_nano_test_ok : R.string.pp_test_ok,
                                sample, text), false));
            }

            @Override public void onFailure(String message) {
                runOnUiThread(() -> finishTest(generation,
                        getString(R.string.pp_test_failed, message), true));
            }
        });
        updateActionButtons();
    }

    private void finishTest(int generation, String message, boolean isError) {
        if (generation != testGeneration || isFinishing()) return;
        testTask = null;
        showTestResult(message, isError);
        updateActionButtons();
    }

    private void clearTest() {
        testGeneration++;
        if (testTask != null) testTask.cancel(true);
        testTask = null;
        testResult.setVisibility(View.GONE);
        updateActionButtons();
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
