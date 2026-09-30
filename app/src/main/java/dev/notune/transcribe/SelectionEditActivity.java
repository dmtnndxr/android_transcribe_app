package dev.notune.transcribe;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;

/** Configures the model and system prompt used by the IME's magic-wand action. */
public class SelectionEditActivity extends AppCompatActivity {
    private static final String TEST_INSTRUCTION = "Make this shorter";
    private static final String TEST_SELECTION =
            "This sentence is longer than it needs to be and could be more concise.";

    private TextInputEditText modelEdit;
    private TextInputEditText promptEdit;
    private TextView inheritedModelText;
    private TextView connectionText;
    private TextView testResult;
    private MaterialButton testButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_selection_edit);

        modelEdit = findViewById(R.id.edit_selection_model);
        promptEdit = findViewById(R.id.edit_selection_prompt);
        inheritedModelText = findViewById(R.id.text_selection_inherited_model);
        connectionText = findViewById(R.id.text_selection_connection);
        testResult = findViewById(R.id.text_selection_test_result);
        testButton = findViewById(R.id.btn_selection_test);

        modelEdit.setText(SelectionEditPrefs.getModelOverride(this));
        promptEdit.setText(SelectionEditPrefs.getSystemPrompt(this));

        findViewById(R.id.btn_selection_open_connection).setOnClickListener(v -> {
            save();
            startActivity(new Intent(this, PostProcessActivity.class));
        });
        findViewById(R.id.btn_selection_use_shared_model).setOnClickListener(v -> {
            modelEdit.setText("");
            SelectionEditPrefs.setModelOverride(this, "");
            updateSharedValues();
            snackbar(getString(R.string.selection_model_reset));
        });
        findViewById(R.id.btn_selection_reset_prompt).setOnClickListener(v -> {
            promptEdit.setText(SelectionEditPrefs.DEFAULT_SYSTEM_PROMPT);
            snackbar(getString(R.string.selection_prompt_reset));
        });
        testButton.setOnClickListener(v -> runTest());
        updateSharedValues();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateSharedValues();
    }

    @Override
    protected void onPause() {
        super.onPause();
        save();
    }

    private void save() {
        SelectionEditPrefs.setModelOverride(this, text(modelEdit));
        String prompt = text(promptEdit);
        SelectionEditPrefs.setSystemPrompt(this,
                prompt.equals(SelectionEditPrefs.DEFAULT_SYSTEM_PROMPT) ? "" : prompt);
    }

    private void updateSharedValues() {
        String sharedModel = PostProcessPrefs.getModel(this);
        inheritedModelText.setText(sharedModel.isEmpty()
                ? getString(R.string.selection_model_missing)
                : getString(R.string.selection_model_inherited, sharedModel));

        if (!PostProcessPrefs.isEnabled(this)) {
            connectionText.setText(R.string.selection_connection_disabled);
        } else if (PostProcessPrefs.getBaseUrl(this).isEmpty()) {
            connectionText.setText(R.string.selection_connection_missing);
        } else {
            connectionText.setText(R.string.selection_connection_ready);
        }
    }

    private void runTest() {
        save();
        final String baseUrl = PostProcessPrefs.getBaseUrl(this);
        final String apiKey = PostProcessPrefs.getApiKey(this);
        final String model = SelectionEditPrefs.getModel(this);
        final String prompt = SelectionEditPrefs.getSystemPrompt(this);
        if (baseUrl.isEmpty() || model.isEmpty()) {
            showTestResult(getString(R.string.selection_test_incomplete), true);
            return;
        }

        testButton.setEnabled(false);
        showTestResult(getString(R.string.pp_test_running), false);
        new Thread(() -> {
            String message;
            boolean error;
            try {
                String output = PostProcessClient.editSelection(baseUrl, apiKey, model,
                        prompt, TEST_INSTRUCTION, TEST_SELECTION);
                message = getString(R.string.selection_test_ok,
                        TEST_INSTRUCTION, TEST_SELECTION, output);
                error = false;
            } catch (PostProcessClient.PostProcessException e) {
                message = getString(R.string.pp_test_failed, e.getMessage());
                error = true;
            }
            final String resultMessage = message;
            final boolean resultError = error;
            runOnUiThread(() -> {
                testButton.setEnabled(true);
                showTestResult(resultMessage, resultError);
            });
        }, "selection-edit-test").start();
    }

    private void showTestResult(String message, boolean error) {
        testResult.setVisibility(View.VISIBLE);
        testResult.setText(message);
        testResult.setTextColor(com.google.android.material.color.MaterialColors.getColor(
                testResult, error
                        ? com.google.android.material.R.attr.colorError
                        : com.google.android.material.R.attr.colorOnSurface));
    }

    private static String text(TextInputEditText edit) {
        return edit.getText() == null ? "" : edit.getText().toString().trim();
    }

    private void snackbar(String message) {
        Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_LONG).show();
    }
}
