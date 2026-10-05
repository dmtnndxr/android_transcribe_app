package dev.notune.transcribe;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * First-run setup. Android doesn't let an app enable its own keyboard or make
 * itself the default voice input, so the wizard explains the one path that
 * fits the user's current keyboard and opens the right system screen.
 */
public class OnboardingActivity extends AppCompatActivity {

    private static final String TAG = "Onboarding";
    private static final String DONE_MARKER = "onboarding_done";
    private static final int REQ_MIC = 301;
    private static final int REQ_VOICE_TEST = 302;

    private static final int STEP_WELCOME = 0;
    private static final int STEP_MIC = 1;
    private static final int STEP_KEYBOARD = 2;
    private static final int STEP_TRY = 3;
    private static final int STEP_AI = 4;
    private static final int STEP_DONE = 5;
    private static final int STEP_COUNT = 6;

    /** How the user's current keyboard reaches us. */
    enum KeyboardKind {
        /** Already the Boice keyboard. */
        OURS,
        /** Its mic key fires RECOGNIZE_SPEECH and opens our voice panel. */
        PANEL,
        /** Its mic key switches to the system's voice keyboard, i.e. ours. */
        SWITCH,
        /** Its mic key only ever uses Google voice typing. */
        GOOGLE_ONLY,
        /** Samsung Keyboard: not verified, most likely like Gboard. */
        SAMSUNG,
        UNKNOWN
    }

    private static final List<String> PANEL_KEYBOARDS = Arrays.asList(
            "com.touchtype.swiftkey", "com.touchtype.swiftkey.beta",
            "com.menny.android.anysoftkeyboard");
    private static final List<String> SWITCH_KEYBOARDS = Arrays.asList(
            "helium314.keyboard", "dev.patrickgold.florisboard",
            "org.dslul.openboard.inputmethod.latin", "org.fossify.keyboard",
            "juloo.keyboard2");
    private static final List<String> GOOGLE_ONLY_KEYBOARDS = Arrays.asList(
            "com.google.android.inputmethod.latin");
    private static final List<String> SAMSUNG_KEYBOARDS = Arrays.asList(
            "com.samsung.android.honeyboard");

    private int step = STEP_WELCOME;
    private String appName;

    private LinearProgressIndicator progress;
    private ImageView image;
    private TextView stepText, title, subtitle, body, example, note, statusText;
    private LinearLayout flow, status;
    private ImageView statusIcon;
    private EditText tryField;
    private MaterialButton action, action2, back, next;

    public static boolean isDone(Context ctx) {
        return new File(ctx.getFilesDir(), DONE_MARKER).exists();
    }

    private void markDone() {
        try {
            new File(getFilesDir(), DONE_MARKER).createNewFile();
        } catch (IOException e) {
            Log.e(TAG, "Failed to save onboarding state", e);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_onboarding);
        appName = getString(R.string.app_name);

        progress = findViewById(R.id.onb_progress);
        image = findViewById(R.id.onb_image);
        stepText = findViewById(R.id.onb_step);
        title = findViewById(R.id.onb_title);
        subtitle = findViewById(R.id.onb_subtitle);
        body = findViewById(R.id.onb_body);
        flow = findViewById(R.id.onb_flow);
        example = findViewById(R.id.onb_example);
        note = findViewById(R.id.onb_note);
        tryField = findViewById(R.id.onb_try_field);
        status = findViewById(R.id.onb_status);
        statusIcon = findViewById(R.id.onb_status_icon);
        statusText = findViewById(R.id.onb_status_text);
        action = findViewById(R.id.onb_action);
        action2 = findViewById(R.id.onb_action2);
        back = findViewById(R.id.onb_back);
        next = findViewById(R.id.onb_next);

        if (savedInstanceState != null) step = savedInstanceState.getInt("step", STEP_WELCOME);

        back.setOnClickListener(v -> go(step - 1));
        next.setOnClickListener(v -> {
            if (step == STEP_DONE) {
                finishWizard();
            } else {
                go(step + 1);
            }
        });
        tryField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                if (step == STEP_TRY) updateTryStatus();
            }
        });
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (step > STEP_WELCOME) {
                    go(step - 1);
                } else {
                    // Leaving on the first screen still counts: don't nag on every launch.
                    finishWizard();
                }
            }
        });
        render();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("step", step);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Back from system settings, the permission dialog or the keyboard picker.
        render();
    }

    private void go(int newStep) {
        if (step == STEP_TRY) hideKeyboard();
        step = Math.max(STEP_WELCOME, Math.min(STEP_DONE, newStep));
        render();
    }

    private void finishWizard() {
        markDone();
        finish();
    }

    // --- Rendering ---------------------------------------------------------

    private void render() {
        progress.setProgressCompat((step + 1) * 100 / STEP_COUNT, true);
        stepText.setText(getString(R.string.onb_step, step + 1, STEP_COUNT));
        back.setVisibility(step == STEP_WELCOME ? View.INVISIBLE : View.VISIBLE);
        next.setText(step == STEP_DONE ? getString(R.string.onb_finish, appName)
                : getString(R.string.onb_next));

        for (View v : new View[]{image, subtitle, flow, example, note, tryField,
                status, action, action2}) {
            v.setVisibility(View.GONE);
        }
        action.setOnClickListener(null);
        action2.setOnClickListener(null);

        switch (step) {
            case STEP_WELCOME: renderWelcome(); break;
            case STEP_MIC: renderMic(); break;
            case STEP_KEYBOARD: renderKeyboard(); break;
            case STEP_TRY: renderTry(); break;
            case STEP_AI: renderAi(); break;
            default: renderDone(); break;
        }
    }

    private void renderWelcome() {
        image.setVisibility(View.VISIBLE);
        title.setText(getString(R.string.onb_welcome_title, appName));
        body.setText(getString(R.string.onb_welcome_body, appName));
    }

    private void renderMic() {
        title.setText(R.string.onb_mic_title);
        body.setText(getString(R.string.onb_mic_body, appName));
        boolean granted = micGranted();
        showStatus(granted, getString(granted ? R.string.onb_mic_done : R.string.onb_mic_missing));
        if (!granted) {
            showAction(action, getString(R.string.onb_mic_action), v ->
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC));
        }
    }

    private void renderKeyboard() {
        title.setText(R.string.onb_kb_title);
        KeyboardKind kind = currentKeyboardKind();
        String label = currentKeyboardLabel();
        if (label != null && kind != KeyboardKind.OURS) {
            subtitle.setVisibility(View.VISIBLE);
            subtitle.setText(getString(R.string.onb_kb_current, label));
        }

        switch (kind) {
            case OURS:
                body.setText(getString(R.string.onb_kb_ours_body, appName));
                showFlow(false);
                break;
            case PANEL:
                body.setText(getString(R.string.onb_kb_panel_body, appName));
                showFlow(true);
                boolean isDefault = isDefaultRecognizer();
                showStatus(isDefault, getString(isDefault
                        ? R.string.onb_kb_panel_ready : R.string.onb_kb_panel_not_yet, appName));
                showAction(isDefault ? action2 : action,
                        getString(R.string.onb_kb_panel_action), v -> launchVoiceTest());
                break;
            case SWITCH:
                body.setText(getString(R.string.onb_kb_switch_body, appName));
                showFlow(false);
                break;
            case GOOGLE_ONLY:
                body.setText(getString(R.string.onb_kb_google_body, appName));
                showFlow(false);
                showNote(getString(R.string.onb_kb_tip_heliboard, appName));
                break;
            case SAMSUNG:
                body.setText(getString(R.string.onb_kb_samsung_body, appName));
                showFlow(false);
                showNote(getString(R.string.onb_kb_tip_heliboard, appName));
                break;
            default:
                body.setText(getString(R.string.onb_kb_unknown_body, appName));
                showFlow(false);
                break;
        }

        if (kind != KeyboardKind.PANEL && kind != KeyboardKind.OURS) {
            boolean enabled = isOurKeyboardEnabled();
            showStatus(enabled, getString(enabled
                    ? R.string.onb_kb_enabled : R.string.onb_kb_not_enabled, appName));
            if (!enabled) {
                showAction(action, getString(R.string.onb_kb_enable, appName),
                        v -> startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));
                if (note.getVisibility() == View.GONE) {
                    showNote(getString(R.string.onb_kb_enable_hint, appName));
                }
            }
        }
    }

    private void renderTry() {
        title.setText(R.string.onb_try_title);
        boolean panel = currentKeyboardKind() == KeyboardKind.PANEL;
        body.setText(getString(panel ? R.string.onb_try_body_panel : R.string.onb_try_body_switch,
                appName));
        tryField.setVisibility(View.VISIBLE);
        if (!panel && isOurKeyboardEnabled()) {
            showAction(action2, getString(R.string.onb_try_pick), v -> {
                tryField.requestFocus();
                InputMethodManager imm = getSystemService(InputMethodManager.class);
                if (imm != null) imm.showInputMethodPicker();
            });
        }
        updateTryStatus();
    }

    private void updateTryStatus() {
        if (tryField.getText().toString().trim().isEmpty()) {
            status.setVisibility(View.GONE);
            return;
        }
        boolean first = status.getVisibility() != View.VISIBLE;
        showStatus(true, getString(R.string.onb_try_ok));
        if (first) {
            // The dictation worked: put the keyboard away so "It works!" and
            // Next are in view. A moment's delay lets the user see the text land.
            tryField.removeCallbacks(hideKeyboardRunnable);
            tryField.postDelayed(hideKeyboardRunnable, 1200);
        }
    }

    private final Runnable hideKeyboardRunnable = this::hideKeyboard;

    private void hideKeyboard() {
        tryField.removeCallbacks(hideKeyboardRunnable);
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(tryField.getWindowToken(), 0);
        tryField.clearFocus();
    }

    private void renderAi() {
        title.setText(R.string.onb_ai_title);
        body.setText(getString(R.string.onb_ai_body, appName));
        example.setVisibility(View.VISIBLE);
        example.setText(R.string.onb_ai_example);
        showNote(getString(R.string.onb_ai_privacy));
        boolean on = PostProcessPrefs.isEnabled(this);
        if (on) showStatus(true, getString(R.string.onb_ai_on));
        showAction(on ? action2 : action, getString(R.string.onb_ai_action),
                v -> startActivity(new Intent(this, PostProcessActivity.class)));
        if (!on) next.setText(R.string.onb_skip);
    }

    private void renderDone() {
        image.setVisibility(View.VISIBLE);
        title.setText(R.string.onb_done_title);
        body.setText(R.string.onb_done_body);
    }

    // --- Pieces --------------------------------------------------------------

    private void showStatus(boolean ok, String message) {
        status.setVisibility(View.VISIBLE);
        statusText.setText(message);
        statusIcon.setImageResource(ok ? R.drawable.ic_check_circle : R.drawable.ic_error);
        int tint = ok ? ContextCompat.getColor(this, R.color.status_ok)
                : themeColor(com.google.android.material.R.attr.colorError);
        ImageViewCompat.setImageTintList(statusIcon, ColorStateList.valueOf(tint));
    }

    private void showAction(MaterialButton button, String text, View.OnClickListener listener) {
        button.setVisibility(View.VISIBLE);
        button.setText(text);
        button.setOnClickListener(listener);
    }

    private void showNote(String text) {
        note.setVisibility(View.VISIBLE);
        note.setText(text);
    }

    /** "How it works" as three icon steps. */
    private void showFlow(boolean keyboardMic) {
        flow.removeAllViews();
        flow.setVisibility(View.VISIBLE);
        if (keyboardMic) {
            addFlowItem(R.drawable.ic_mic, getString(R.string.onb_flow_keymic));
        } else {
            addFlowItem(R.drawable.ic_keyboard_switch, getString(R.string.onb_flow_switch, appName));
            addFlowItem(R.drawable.ic_mic, getString(R.string.onb_flow_mic));
        }
        addFlowItem(R.drawable.ic_subtitles, getString(R.string.onb_flow_text));
    }

    private void addFlowItem(int icon, String label) {
        float dp = getResources().getDisplayMetrics().density;
        if (flow.getChildCount() > 0) {
            TextView arrow = new TextView(this);
            arrow.setText("→");
            arrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            arrow.setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant));
            arrow.setPadding(0, (int) (14 * dp), 0, 0);
            arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            flow.addView(arrow);
        }
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        item.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        ImageView iv = new ImageView(this);
        int size = (int) (52 * dp);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(size, size);
        iv.setLayoutParams(ip);
        iv.setImageResource(icon);
        int pad = (int) (13 * dp);
        iv.setPadding(pad, pad, pad, pad);
        iv.setBackgroundResource(R.drawable.bg_ime_mini_mic);
        iv.setBackgroundTintList(ColorStateList.valueOf(
                themeColor(com.google.android.material.R.attr.colorSecondaryContainer)));
        ImageViewCompat.setImageTintList(iv, ColorStateList.valueOf(
                themeColor(com.google.android.material.R.attr.colorOnSecondaryContainer)));
        iv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        item.addView(iv);

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setGravity(Gravity.CENTER_HORIZONTAL);
        tv.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        tv.setPadding(0, (int) (6 * dp), 0, 0);
        item.addView(tv);
        flow.addView(item);
    }

    private int themeColor(int attr) {
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(attr, tv, true);
        return tv.resourceId != 0 ? ContextCompat.getColor(this, tv.resourceId) : tv.data;
    }

    // --- System state ----------------------------------------------------------

    private boolean micGranted() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private String currentKeyboardId() {
        return Settings.Secure.getString(getContentResolver(),
                Settings.Secure.DEFAULT_INPUT_METHOD);
    }

    KeyboardKind currentKeyboardKind() {
        String id = currentKeyboardId();
        if (id == null) return KeyboardKind.UNKNOWN;
        String pkg = id.contains("/") ? id.substring(0, id.indexOf('/')) : id;
        if (pkg.equals(getPackageName())) return KeyboardKind.OURS;
        if (PANEL_KEYBOARDS.contains(pkg)) return KeyboardKind.PANEL;
        if (SWITCH_KEYBOARDS.contains(pkg)) return KeyboardKind.SWITCH;
        if (GOOGLE_ONLY_KEYBOARDS.contains(pkg)) return KeyboardKind.GOOGLE_ONLY;
        if (SAMSUNG_KEYBOARDS.contains(pkg)) return KeyboardKind.SAMSUNG;
        return KeyboardKind.UNKNOWN;
    }

    private String currentKeyboardLabel() {
        String id = currentKeyboardId();
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (id == null || imm == null) return null;
        for (InputMethodInfo info : imm.getInputMethodList()) {
            if (id.equals(info.getId())) {
                return info.loadLabel(getPackageManager()).toString();
            }
        }
        return null;
    }

    private boolean isOurKeyboardEnabled() {
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm == null) return false;
        for (InputMethodInfo info : imm.getEnabledInputMethodList()) {
            if (getPackageName().equals(info.getPackageName())) return true;
        }
        return false;
    }

    private boolean isDefaultRecognizer() {
        ResolveInfo resolved = getPackageManager().resolveActivity(
                new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH),
                PackageManager.MATCH_DEFAULT_ONLY);
        return resolved != null && resolved.activityInfo != null
                && getPackageName().equals(resolved.activityInfo.packageName);
    }

    /** Same intent a keyboard's mic fires; lets the user pick us and "Always". */
    private void launchVoiceTest() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        try {
            startActivityForResult(intent, REQ_VOICE_TEST);
        } catch (android.content.ActivityNotFoundException e) {
            Log.w(TAG, "No RECOGNIZE_SPEECH handler", e);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        render();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        render();
    }
}
