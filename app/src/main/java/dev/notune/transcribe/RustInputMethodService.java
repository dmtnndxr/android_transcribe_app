package dev.notune.transcribe;

import android.inputmethodservice.InputMethodService;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ProgressBar;
import android.view.Gravity;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.content.Context;
import android.content.pm.PackageManager;
import android.view.MotionEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.text.InputType;
import android.content.res.ColorStateList;
import android.view.ContextThemeWrapper;
import java.io.File;

import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.MaterialColors;

public class RustInputMethodService extends InputMethodService {
    
    private static final String TAG = "OfflineVoiceInput";

    static {
        try {
            System.loadLibrary("c++_shared");
            System.loadLibrary("android_transcribe_app");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native libraries", e);
        }
    }

    private TextView statusView;
    private TextView hintView;
    private View recordContainer;
    private android.widget.ImageView micIcon;
    private ProgressBar progressBar;
    private View backspaceButton;
    private View spaceButton;
    private View enterButton;
    private View switchKeyboardButton;
    private LinearLayout punctuationRow;
    private View inputView;
    private MicLevelView micLevelView;
    private View recordCircle;
    // Secondary "AI cleanup" mic: same capture path, but the transcription is
    // sent to the configured LLM before it is inserted.
    private View aiContainer;
    private android.widget.ImageView aiMicIcon;
    private TextView aiLabel;
    private View editContainer;
    private android.widget.ImageView editSelectionButton;
    private TextView editLabel;
    private Button cancelAction;
    private Button undoAction;
    /**
     * Whether the recording in flight (or about to start) should be
     * post-processed. Latched when the mic is tapped so a settings change
     * mid-recording can't switch modes underneath the user.
     */
    private boolean postProcessNext = false;
    /** True while an LLM request is outstanding, to keep both mics disabled. */
    private boolean postProcessRunning = false;
    /** True after capture stops and until the native transcription callback arrives. */
    private boolean transcriptionPending = false;
    /** A canceled native transcription cannot always be interrupted; discard its callback. */
    private boolean discardNextTranscription = false;
    private boolean cancelRequested = false;
    /** The third capture mode: a spoken instruction over a frozen selection. */
    private boolean editSelectionNext = false;
    private SelectionSnapshot selectionToEdit = null;
    /** Invalidates an outstanding LLM callback without needing to kill its worker thread. */
    private long llmGeneration = 0;
    /** Changes whenever Android attaches this IME to another editor. */
    private long editorGeneration = 0;
    private UndoState undoState = null;
    private static final long UNDO_TIMEOUT_MS = 12_000;
    private final Runnable expireUndo = () -> clearUndo();
    // Night flag the current input view was inflated with, so it can be rebuilt
    // if the theme preference changes while this process stays alive.
    private boolean viewIsNight = false;
    private Handler mainHandler;
    private boolean isRecording = false;
    private boolean pendingSwitchBack = false;
    private String lastStatus = "Initializing...";
    // Key repeat settings
    private static final long REPEAT_INITIAL_DELAY = 400; // ms before repeat starts
    private static final long REPEAT_INTERVAL = 50; // ms between repeats
    private Runnable backspaceRepeatRunnable;
    private Runnable spaceRepeatRunnable;
    private final AudioFocusPauser audioPauser = new AudioFocusPauser();
    private boolean pauseAudioActive = false;
    // Whether an editor is currently focused/started for input. Tracked via
    // onStartInput/onFinishInput because getCurrentInputConnection() returns a
    // non-null no-op connection when nothing is focused, so commitText would be
    // silently dropped.
    private boolean inputActive = false;
    // Whether the keyboard window is currently on screen. Some frameworks
    // (notably OEM builds) call onWindowShown again for events that don't
    // follow an onWindowHidden, e.g. tapping the text area to move the
    // cursor while the keyboard stays visible. Auto-record must only fire on
    // a genuine hidden -> shown transition, or a cursor tap starts a
    // recording the user never asked for.
    private boolean windowVisible = false;
    // Transcribed text waiting to be committed because no editor was focused
    // when transcription finished. This happens on long transcribes where the
    // target field (e.g. a web field in Firefox/Gemini) drops focus while we
    // process audio. Flushed from onStartInputView once a field is focused
    // again so the text is never lost.
    private String pendingCommitText = null;

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        Log.d(TAG, "Service onCreate");
        try {
            initNative(this);
        } catch (Throwable t) {
            // Native may be unavailable (e.g. wrong-ABI emulator); don't crash the IME.
            Log.e(TAG, "Error in initNative", t);
        }
    }

    @Override
    public View onCreateInputView() {
        Log.d(TAG, "onCreateInputView");
        try {
            // The IME is a non-AppCompat Service in a separate process, so
            // AppCompat's delegate can't theme it. Build a context that is
            // night-aware (per the saved preference), wears the Material 3 theme,
            // and picks up Material You dynamic color — matching the app.
            Context night = ThemePrefs.wrapForNight(this, ThemePrefs.getMode(this));
            viewIsNight = ThemePrefs.isNight(night);
            Context themed = DynamicColors.wrapContextIfAvailable(
                    new ContextThemeWrapper(night, R.style.AppTheme));
            View view = LayoutInflater.from(themed).inflate(R.layout.ime_layout, null);
            inputView = view;

            // Handle window insets for avoiding navigation bar overlap
            view.setOnApplyWindowInsetsListener((v, insets) -> {
                int paddingBottom = insets.getSystemWindowInsetBottom();
                int originalPaddingBottom = v.getPaddingTop();
                v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), originalPaddingBottom + paddingBottom);
                return insets;
            });

            statusView = view.findViewById(R.id.ime_status_text);
            progressBar = view.findViewById(R.id.ime_progress);
            recordContainer = view.findViewById(R.id.ime_record_container);
            micIcon = view.findViewById(R.id.ime_mic_icon);
            micLevelView = view.findViewById(R.id.ime_mic_level);
            recordCircle = view.findViewById(R.id.ime_record_circle);
            hintView = view.findViewById(R.id.ime_hint);
            backspaceButton = view.findViewById(R.id.ime_backspace);
            spaceButton = view.findViewById(R.id.ime_space);
            enterButton = view.findViewById(R.id.ime_enter);
            switchKeyboardButton = view.findViewById(R.id.ime_switch_keyboard);
            punctuationRow = view.findViewById(R.id.ime_punctuation_row);
            aiContainer = view.findViewById(R.id.ime_ai_container);
            aiMicIcon = view.findViewById(R.id.ime_ai_mic);
            aiLabel = view.findViewById(R.id.ime_ai_label);
            editContainer = view.findViewById(R.id.ime_edit_container);
            editSelectionButton = view.findViewById(R.id.ime_edit_selection);
            editLabel = view.findViewById(R.id.ime_edit_label);
            cancelAction = view.findViewById(R.id.ime_cancel_action);
            undoAction = view.findViewById(R.id.ime_undo_action);

            updatePunctuationKeys();

            switchKeyboardButton.setOnClickListener(v -> {
                if (isRecording) {
                    pendingSwitchBack = true;
                    transcriptionPending = true;
                    stopRecording();
                    updateRecordButtonUI(false);
                } else {
                    switchToPreviousInputMethod();
                }
            });

            // Key repeat runnable for backspace
            backspaceRepeatRunnable = new Runnable() {
                @Override
                public void run() {
                    InputConnection ic = getCurrentInputConnection();
                    if (ic != null) {
                        ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DEL));
                        ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DEL));
                    }
                    mainHandler.postDelayed(this, REPEAT_INTERVAL);
                }
            };

            // Key repeat runnable for space
            spaceRepeatRunnable = new Runnable() {
                @Override
                public void run() {
                    InputConnection ic = getCurrentInputConnection();
                    if (ic != null) {
                        ic.commitText(" ", 1);
                    }
                    mainHandler.postDelayed(this, REPEAT_INTERVAL);
                }
            };

            backspaceButton.setOnTouchListener((v, event) -> {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        InputConnection ic = getCurrentInputConnection();
                        if (ic != null) {
                            ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DEL));
                            ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DEL));
                        }
                        mainHandler.postDelayed(backspaceRepeatRunnable, REPEAT_INITIAL_DELAY);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        mainHandler.removeCallbacks(backspaceRepeatRunnable);
                        return true;
                }
                return false;
            });

            spaceButton.setOnTouchListener((v, event) -> {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        InputConnection ic = getCurrentInputConnection();
                        if (ic != null) {
                            ic.commitText(" ", 1);
                        }
                        mainHandler.postDelayed(spaceRepeatRunnable, REPEAT_INITIAL_DELAY);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        mainHandler.removeCallbacks(spaceRepeatRunnable);
                        return true;
                }
                return false;
            });

            enterButton.setOnClickListener(v -> {
                InputConnection ic = getCurrentInputConnection();
                if (ic != null) {
                    android.view.inputmethod.EditorInfo editorInfo = getCurrentInputEditorInfo();
                    if (editorInfo == null) {
                        ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER));
                        ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER));
                        return;
                    }
                    int imeOptions = editorInfo.imeOptions;
                    int action = imeOptions & android.view.inputmethod.EditorInfo.IME_MASK_ACTION;
                    boolean noEnterAction = (imeOptions & android.view.inputmethod.EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0;

                    // If the editor flags IME_FLAG_NO_ENTER_ACTION (e.g. multi-line fields in
                    // messaging apps like Signal), or if there's no meaningful action, insert a
                    // newline. Otherwise perform the editor action (Go, Search, Send, etc.).
                    if (!noEnterAction && (
                            action == android.view.inputmethod.EditorInfo.IME_ACTION_GO ||
                            action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH ||
                            action == android.view.inputmethod.EditorInfo.IME_ACTION_SEND ||
                            action == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT)) {
                        ic.performEditorAction(action);
                    } else {
                        ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER));
                        ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER));
                    }
                }
            });

            recordContainer.setOnClickListener(v -> onMicTap(false));
            if (aiMicIcon != null) {
                aiMicIcon.setOnClickListener(v -> onMicTap(true));
            }
            if (editSelectionButton != null) {
                editSelectionButton.setOnClickListener(v -> onEditSelectionTap());
            }
            if (cancelAction != null) cancelAction.setOnClickListener(v -> cancelCurrentOperation());
            if (undoAction != null) undoAction.setOnClickListener(v -> undoLastChange());

            tintRecordButton(false);
            tintAiMic(false);
            updateAiMicVisibility();
            updateEditSelectionEnabled();
            updateUiState();
            return view;
        } catch (Exception e) {
            Log.e(TAG, "Error in onCreateInputView", e);
            TextView errorView = new TextView(this);
            errorView.setText("Error loading keyboard: " + e.getMessage());
            return errorView;
        }
    }

    @Override
    public void onWindowShown() {
        super.onWindowShown();
        boolean wasVisible = windowVisible;
        windowVisible = true;
        if (isRecording) {
            // A background recording is still running (record-in-background
            // setting): restore the recording UI.
            updateRecordButtonUI(true);
            return;
        }
        if (wasVisible) {
            // Not a real hidden -> shown transition (e.g. a cursor tap in the
            // text area); never auto-start a recording from here.
            return;
        }
        if (new File(getFilesDir(), "auto_record").exists()) {
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                if (isPauseAudioEnabled()) {
                    audioPauser.request(this);
                    pauseAudioActive = true;
                }
                // Auto-start is the plain path: post-processing is opt-in per
                // recording, via its own button.
                postProcessNext = false;
                editSelectionNext = false;
                selectionToEdit = null;
                clearUndo();
                startRecording();
                updateRecordButtonUI(true);
            }
        }
    }

    @Override
    public void onWindowHidden() {
        super.onWindowHidden();
        windowVisible = false;
        if (isRecording) {
            if (isStopOnHideEnabled()) {
                // Opt-in behavior: discard the recording when the keyboard hides.
                boolean fellBackToTranscription = false;
                try {
                    cancelRecording();
                } catch (Throwable t) {
                    Log.w(TAG, "cancelRecording failed, falling back to stopRecording", t);
                    try {
                        stopRecording();
                        fellBackToTranscription = true;
                    } catch (Throwable ignored) { }
                }
                postProcessNext = false;
                editSelectionNext = false;
                selectionToEdit = null;
                transcriptionPending = fellBackToTranscription;
                discardNextTranscription = fellBackToTranscription;
                cancelRequested = fellBackToTranscription;
                updateRecordButtonUI(false);
            } else {
                // Default: keep recording in the background. The transcription
                // is committed on return (or held in pendingCommitText).
                return;
            }
        }
        if (pauseAudioActive) {
            audioPauser.abandon(this);
            pauseAudioActive = false;
        }
    }

    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute, restarting);
        editorGeneration++;
        clearUndo();
        inputActive = true;
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        inputActive = true;
        // Rebuild the keyboard if the theme preference changed while this
        // (long-lived) IME process stayed alive, so it matches the app setting.
        if (inputView != null
                && ThemePrefs.isNight(ThemePrefs.wrapForNight(this, ThemePrefs.getMode(this))) != viewIsNight) {
            setInputView(onCreateInputView());
        }
        // Post-processing may have been switched on or off in the app since this
        // (long-lived) IME process last inflated its view.
        updateAiMicVisibility();
        // The set of manual editing keys may have changed in the app while the
        // long-lived IME process was in the background.
        updatePunctuationKeys();
        // A field is focused and the input connection is live again — commit any
        // text that finished transcribing while nothing was focused.
        flushPendingText();
        updateEditSelectionEnabled();
    }

    @Override
    public void onFinishInput() {
        super.onFinishInput();
        editorGeneration++;
        clearUndo();
        inputActive = false;
    }

    @Override
    public void onUpdateSelection(int oldSelStart, int oldSelEnd,
                                  int newSelStart, int newSelEnd,
                                  int candidatesStart, int candidatesEnd) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd,
                candidatesStart, candidatesEnd);
        updateEditSelectionEnabled();
    }

    /**
     * Shared handler for both mics. {@code postProcess} selects the AI-cleanup
     * variant; capture itself is identical either way, so the flag only decides
     * what happens to the text once transcription finishes. Either mic can stop
     * a running recording, but the mode chosen when recording began is retained.
     */
    private void onMicTap(boolean postProcess) {
        if (isRecording) {
            // Stopping from a sibling button must not silently change the mode
            // chosen when capture began (especially selection editing).
            transcriptionPending = true;
            stopRecording();
            if (pauseAudioActive) {
                audioPauser.abandon(this);
                pauseAudioActive = false;
            }
            updateRecordButtonUI(false);
            return;
        }

        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            if (statusView != null) statusView.setText("No mic permission - grant in app");
            if (hintView != null) hintView.setText("Open the app to grant permission");
            return;
        }

        clearUndo();
        postProcessNext = postProcess;
        editSelectionNext = false;
        selectionToEdit = null;
        discardNextTranscription = false;
        cancelRequested = false;
        if (isPauseAudioEnabled()) {
            audioPauser.request(this);
            pauseAudioActive = true;
        }
        startRecording();
        updateRecordButtonUI(true);
    }

    /** Starts recording a one-off instruction for the currently selected text. */
    private void onEditSelectionTap() {
        if (isRecording) {
            onMicTap(false);
            return;
        }
        if (!SelectionEditPrefs.isConfigured(this)) {
            showStatus(R.string.ime_edit_not_configured);
            return;
        }
        if (isSecureEditor()) {
            showStatus(R.string.ime_edit_password);
            return;
        }
        InputConnection ic = getCurrentInputConnection();
        SelectionSnapshot snapshot = captureSelection(ic, true);
        if (snapshot == null) {
            showStatus(R.string.ime_edit_select_first);
            return;
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            if (statusView != null) statusView.setText("No mic permission - grant in app");
            return;
        }

        clearUndo();
        selectionToEdit = snapshot;
        editSelectionNext = true;
        postProcessNext = false;
        discardNextTranscription = false;
        cancelRequested = false;
        if (isPauseAudioEnabled()) {
            audioPauser.request(this);
            pauseAudioActive = true;
        }
        startRecording();
        updateRecordButtonUI(true);
    }

    private void updateRecordButtonUI(boolean recording) {
        isRecording = recording;
        // Keep the screen awake while recording so it never sleeps mid-capture
        // and cuts the recording short. Cleared automatically once we stop.
        if (inputView != null) {
            inputView.setKeepScreenOn(recording);
        }
        boolean editActive = recording && editSelectionNext;
        boolean aiActive = recording && postProcessNext && !editActive;
        tintRecordButton(recording && !postProcessNext && !editActive);
        tintAiMic(aiActive);
        tintEditButton(editActive);
        if (recording) {
            statusView.setText(editActive ? getString(R.string.ime_edit_listening)
                    : aiActive ? getString(R.string.ime_ai_listening) : "Listening...");
            hintView.setText("Tap to Stop");
            if (aiLabel != null) {
                aiLabel.setText(aiActive ? R.string.ime_stop_label : R.string.ime_ai_mic_label);
            }
        } else {
            statusView.setText("Processing...");
            hintView.setText("Tap to Record");
            if (aiLabel != null) aiLabel.setText(R.string.ime_ai_mic_label);
            if (editLabel != null) editLabel.setText(R.string.ime_edit_selection_label);
            if (micLevelView != null) micLevelView.setLevel(0f);
        }
        applyMicEnabledState();
    }

    /** Shows the AI mic only when post-processing is switched on in the app. */
    private void updateAiMicVisibility() {
        boolean visible = PostProcessPrefs.isEnabled(this);
        if (aiContainer != null) {
            aiContainer.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
        if (editContainer != null) {
            editContainer.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    /**
     * Single source of truth for which mics are tappable. Both are locked out
     * while the engine or the LLM is busy; during a recording both stay live,
     * since either one can stop it (see {@link #onMicTap}).
     */
    private void applyMicEnabledState() {
        boolean busy = postProcessRunning || transcriptionPending
                || lastStatus.contains("Transcribing")
                || lastStatus.contains("Processing")
                || lastStatus.contains("Waiting")
                || lastStatus.startsWith("Error");

        boolean mainEnabled = !busy;
        boolean aiEnabled = !busy;

        if (recordContainer != null) {
            recordContainer.setEnabled(mainEnabled);
            recordContainer.setAlpha(mainEnabled ? 1.0f : 0.5f);
        }
        if (aiContainer != null && aiMicIcon != null) {
            aiMicIcon.setEnabled(aiEnabled);
            aiContainer.setAlpha(aiEnabled ? 1.0f : 0.5f);
        }
        if (editContainer != null && editSelectionButton != null) {
            boolean editTappable = !busy;
            boolean selectionReady = !isSecureEditor() && hasSelection();
            editSelectionButton.setEnabled(editTappable);
            editContainer.setAlpha(selectionReady && editTappable ? 1.0f : 0.45f);
        }
        if (cancelAction != null) {
            boolean cancelable = (isRecording || transcriptionPending || postProcessRunning)
                    && !cancelRequested;
            cancelAction.setVisibility(cancelable ? View.VISIBLE : View.GONE);
        }
    }

    /** Tints the round record button + mic: idle = primary, recording = error. */
    private void tintRecordButton(boolean recording) {
        int circleAttr = recording
                ? com.google.android.material.R.attr.colorPrimary
                : com.google.android.material.R.attr.colorPrimaryContainer;
        int iconAttr = recording
                ? com.google.android.material.R.attr.colorOnPrimary
                : com.google.android.material.R.attr.colorOnPrimaryContainer;
        if (recordCircle != null) {
            recordCircle.setBackgroundTintList(ColorStateList.valueOf(
                    MaterialColors.getColor(recordCircle, circleAttr)));
        }
        if (micIcon != null) {
            micIcon.setColorFilter(MaterialColors.getColor(micIcon, iconAttr));
        }
    }

    /**
     * Tints the AI mic. Uses the tertiary role so it reads as a sibling of the
     * main record button rather than a duplicate of it, and fills in solid while
     * it is the one recording.
     */
    private void tintAiMic(boolean recording) {
        if (aiMicIcon == null) return;
        int circleAttr = recording
                ? com.google.android.material.R.attr.colorTertiary
                : com.google.android.material.R.attr.colorTertiaryContainer;
        int iconAttr = recording
                ? com.google.android.material.R.attr.colorOnTertiary
                : com.google.android.material.R.attr.colorOnTertiaryContainer;
        aiMicIcon.setBackgroundTintList(ColorStateList.valueOf(
                MaterialColors.getColor(aiMicIcon, circleAttr)));
        aiMicIcon.setColorFilter(MaterialColors.getColor(aiMicIcon, iconAttr));
    }

    private void tintEditButton(boolean recording) {
        if (editSelectionButton == null) return;
        int circleAttr = recording
                ? com.google.android.material.R.attr.colorSecondary
                : com.google.android.material.R.attr.colorSecondaryContainer;
        int iconAttr = recording
                ? com.google.android.material.R.attr.colorOnSecondary
                : com.google.android.material.R.attr.colorOnSecondaryContainer;
        editSelectionButton.setBackgroundTintList(ColorStateList.valueOf(
                MaterialColors.getColor(editSelectionButton, circleAttr)));
        editSelectionButton.setColorFilter(MaterialColors.getColor(editSelectionButton, iconAttr));
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mainHandler != null) mainHandler.removeCallbacks(expireUndo);
        llmGeneration++;
        cleanupNative();
        if (pauseAudioActive) {
            audioPauser.abandon(this);
            pauseAudioActive = false;
        }
    }

    // Native methods
    private native void initNative(RustInputMethodService service);
    private native void cleanupNative();
    private native void startRecording();
    private native void stopRecording();
    private native void cancelRecording();

    // Called from Rust
    public void onStatusUpdate(String status) {
        mainHandler.post(() -> {
            Log.d(TAG, "Status: " + status);
            lastStatus = status;
            if (status != null && status.startsWith("Error")) {
                isRecording = false;
                if (inputView != null) inputView.setKeepScreenOn(false);
                transcriptionPending = false;
                discardNextTranscription = false;
                cancelRequested = false;
                editSelectionNext = false;
                selectionToEdit = null;
                tintRecordButton(false);
                tintAiMic(false);
                tintEditButton(false);
            }
            updateUiState();
            if (pendingSwitchBack && status.startsWith("Error")) {
                pendingSwitchBack = false;
                switchToPreviousInputMethod();
            }
            if (pauseAudioActive && status != null && status.startsWith("Error")) {
                audioPauser.abandon(this);
                pauseAudioActive = false;
            }
        });
    }

    private void updateUiState() {
        boolean isLoading = lastStatus.contains("Loading") || lastStatus.contains("Initializing");
        boolean isWaiting = lastStatus.contains("Waiting");
        boolean isTranscribing = lastStatus.contains("Transcribing") || lastStatus.contains("Processing");
        boolean isError = lastStatus.startsWith("Error");
        boolean isReady = lastStatus.equals("Ready");

        // Don't show internal loading states to the user, and don't clobber the
        // Keep the AI-processing line while an LLM request is still in flight.
        if (statusView != null && !isRecording && !postProcessRunning && !cancelRequested) {
            if (isError) {
                statusView.setText(lastStatus);
            } else if (isTranscribing || isWaiting) {
                statusView.setText("Processing...");
            } else {
                statusView.setText("Tap to Record");
            }
        }

        // Hide progress bar - don't expose model loading to user
        if (progressBar != null) {
            progressBar.setVisibility(View.GONE);
        }

        // Disable the mics during transcription/processing/waiting or fatal errors
        applyMicEnabledState();

        if (hintView != null && !isRecording && !postProcessRunning && !cancelRequested) {
            hintView.setText("Tap to Record");
        }
    }

    // Called from Rust while a streaming model transcribes during recording.
    public void onPartialText(String text) {
        mainHandler.post(() -> {
            if (!isRecording || statusView == null || text == null) return;
            String live = liveTail(text);
            if (!live.isEmpty()) statusView.setText(live);
        });
    }

    /**
     * The end of the running transcript, which is the part being spoken right
     * now; a long dictation would otherwise push it out of the status line.
     */
    static String liveTail(String text) {
        String t = text.trim();
        return t.length() <= 80 ? t : "…" + t.substring(t.length() - 80);
    }

    // Called from Rust
    public void onTextTranscribed(String text) {
        mainHandler.post(() -> {
            boolean postProcess = postProcessNext;
            boolean editSelection = editSelectionNext;
            postProcessNext = false;
            editSelectionNext = false;
            transcriptionPending = false;

            if (discardNextTranscription) {
                discardNextTranscription = false;
                cancelRequested = false;
                selectionToEdit = null;
                finishOperation(getString(R.string.ime_canceled));
                return;
            }

            if (text == null || text.trim().isEmpty()) {
                // Nothing recognized — don't insert a stray space, and don't
                // spend an LLM round-trip on an empty string.
                selectionToEdit = null;
                updateRecordButtonUI(false);
                if (statusView != null) statusView.setText("Tap to Record");
                if (pauseAudioActive) {
                    audioPauser.abandon(this);
                    pauseAudioActive = false;
                }
                if (pendingSwitchBack) {
                    pendingSwitchBack = false;
                    switchToPreviousInputMethod();
                }
                return;
            }

            if (editSelection) {
                if (selectionToEdit == null) {
                    finishOperation(getString(R.string.ime_edit_selection_changed));
                } else {
                    startSelectionPostProcessing(text);
                }
                return;
            }

            if (postProcess) {
                if (PostProcessPrefs.isConfigured(this)) {
                    startPostProcessing(text);
                } else {
                    // The AI mic was tapped but there's no endpoint/model set.
                    // Insert what we heard rather than dropping the user's words.
                    deliverText(text, getString(R.string.ime_ai_not_configured));
                }
                return;
            }

            deliverText(text, null);
        });
    }

    /**
     * Sends the transcription to the configured LLM, then inserts the result.
     * Any failure falls back to the raw transcription with the reason shown in
     * the status line — post-processing is a bonus, never a gate on getting the
     * user's words into the field.
     */
    private void startPostProcessing(String rawText) {
        postProcessRunning = true;
        cancelRequested = false;
        final long requestGeneration = ++llmGeneration;
        updateRecordButtonUI(false);
        // Recording is over, so hand audio focus back now instead of making the
        // user's music wait out the network round-trip.
        if (pauseAudioActive) {
            audioPauser.abandon(this);
            pauseAudioActive = false;
        }
        if (statusView != null) statusView.setText(R.string.ime_ai_working);
        if (hintView != null) hintView.setText(R.string.ime_ai_working_hint);
        applyMicEnabledState();

        PostProcessor.processAsync(this, rawText, new PostProcessor.Callback() {
            @Override
            public void onSuccess(String processed) {
                mainHandler.post(() -> {
                    if (requestGeneration != llmGeneration) return;
                    postProcessRunning = false;
                    deliverText(processed, null);
                });
            }

            @Override
            public void onFailure(String message) {
                mainHandler.post(() -> {
                    if (requestGeneration != llmGeneration) return;
                    postProcessRunning = false;
                    Log.w(TAG, "Post-processing failed, inserting raw text: " + message);
                    deliverText(rawText, getString(R.string.ime_ai_failed, message));
                });
            }
        });
    }

    /** Runs the spoken instruction and replaces only the still-identical selection. */
    private void startSelectionPostProcessing(String instruction) {
        final SelectionSnapshot target = selectionToEdit;
        postProcessRunning = true;
        cancelRequested = false;
        final long requestGeneration = ++llmGeneration;
        updateRecordButtonUI(false);
        if (pauseAudioActive) {
            audioPauser.abandon(this);
            pauseAudioActive = false;
        }
        if (statusView != null) statusView.setText(R.string.ime_edit_working);
        if (hintView != null) hintView.setText(R.string.ime_edit_working_hint);
        applyMicEnabledState();

        PostProcessor.editSelectionAsync(this, instruction, target.text,
                new PostProcessor.Callback() {
            @Override
            public void onSuccess(String processed) {
                mainHandler.post(() -> {
                    if (requestGeneration != llmGeneration) return;
                    postProcessRunning = false;
                    selectionToEdit = null;
                    applySelectionEdit(target, processed);
                });
            }

            @Override
            public void onFailure(String message) {
                mainHandler.post(() -> {
                    if (requestGeneration != llmGeneration) return;
                    postProcessRunning = false;
                    selectionToEdit = null;
                    finishOperation(getString(R.string.ime_edit_failed, message));
                });
            }
        });
    }

    /**
     * Final step for both paths: commit the text (or hold it until a field is
     * focused again) and reset the keyboard UI.
     *
     * @param statusMessage shown instead of the usual idle prompt, for reporting
     *                      a post-processing failure. Pass null for the default.
     */
    private void deliverText(String text, String statusMessage) {
        String committed = text + " ";
        InputConnection ic = getCurrentInputConnection();
        if (inputActive && ic != null) {
            commitTranscribedText(ic, committed);
        } else {
            // No editor is focused right now (common on long transcribes where
            // a web field in Firefox/Gemini dropped focus while we processed
            // audio, and more likely still once an LLM round-trip is added).
            // Committing now would be silently dropped, so defer the text until
            // a field is focused again instead of losing it.
            pendingCommitText = committed;
        }
        if (pauseAudioActive) {
            audioPauser.abandon(this);
            pauseAudioActive = false;
        }
        updateRecordButtonUI(false);
        if (statusView != null) {
            statusView.setText(statusMessage != null
                    ? statusMessage : getString(R.string.ime_text_inserted));
        }
        if (pendingSwitchBack) {
            pendingSwitchBack = false;
            switchToPreviousInputMethod();
        }
    }

    // Commits transcribed text into the active input connection, optionally
    // selecting it afterwards (select_transcription setting).
    private void commitTranscribedText(InputConnection ic, String committed) {
        SelectionSnapshot before = captureSelection(ic, false);
        ic.commitText(committed, 1);

        if (before != null) {
            offerUndo(before.text, committed, before.start, before.editorGeneration);
        }

        if (!pendingSwitchBack && new File(getFilesDir(), "select_transcription").exists()) {
            android.view.inputmethod.ExtractedText et = ic.getExtractedText(
                new android.view.inputmethod.ExtractedTextRequest(), 0);
            if (et != null) {
                int end = et.startOffset + et.selectionStart;
                int start = end - committed.length();
                if (start >= 0) {
                    ic.setSelection(start, end);
                }
            }
        }
    }

    // Commits text that finished transcribing while no field was focused. Called
    // from onStartInputView when an editor (and a live input connection) is
    // available again.
    private void flushPendingText() {
        if (pendingCommitText == null) return;
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            commitTranscribedText(ic, pendingCommitText);
            pendingCommitText = null;
        }
    }

    /** Cancels capture immediately, or makes an already-running result a no-op. */
    private void cancelCurrentOperation() {
        clearUndo();
        if (isRecording) {
            boolean fellBackToTranscription = false;
            try {
                cancelRecording();
            } catch (Throwable t) {
                Log.w(TAG, "Couldn't cancel recording", t);
                try {
                    stopRecording();
                    fellBackToTranscription = true;
                } catch (Throwable stopError) {
                    Log.w(TAG, "Couldn't stop recording after cancel failed", stopError);
                }
            }
            postProcessNext = false;
            editSelectionNext = false;
            selectionToEdit = null;
            discardNextTranscription = fellBackToTranscription;
            transcriptionPending = fellBackToTranscription;
            cancelRequested = fellBackToTranscription;
            if (pauseAudioActive) {
                audioPauser.abandon(this);
                pauseAudioActive = false;
            }
            isRecording = false;
            if (inputView != null) inputView.setKeepScreenOn(false);
            tintRecordButton(false);
            tintAiMic(false);
            tintEditButton(false);
            finishOperation(getString(fellBackToTranscription
                    ? R.string.ime_canceling : R.string.ime_canceled));
            if (fellBackToTranscription) {
                cancelRequested = true;
                applyMicEnabledState();
            }
            return;
        }
        if (transcriptionPending) {
            // The offline engine owns a copied audio buffer now. Its current API
            // has no post-stop interrupt, so keep the UI locked until its callback
            // arrives and guarantee that callback is discarded.
            discardNextTranscription = true;
            cancelRequested = true;
            selectionToEdit = null;
            if (statusView != null) statusView.setText(R.string.ime_canceling);
            if (hintView != null) hintView.setText(R.string.ime_canceling);
            applyMicEnabledState();
            return;
        }
        if (postProcessRunning) {
            llmGeneration++;
            postProcessRunning = false;
            cancelRequested = false;
            selectionToEdit = null;
            finishOperation(getString(R.string.ime_canceled));
        }
    }

    private void applySelectionEdit(SelectionSnapshot target, String replacement) {
        InputConnection ic = getCurrentInputConnection();
        SelectionSnapshot current = captureSelection(ic, true);
        if (current == null
                || current.editorGeneration != target.editorGeneration
                || current.start != target.start
                || current.end != target.end
                || !current.text.equals(target.text)) {
            finishOperation(getString(R.string.ime_edit_selection_changed));
            return;
        }
        if (target.text.equals(replacement)) {
            finishOperation(getString(R.string.ime_edit_no_change));
            return;
        }

        ic.beginBatchEdit();
        try {
            ic.commitText(replacement, 1);
        } finally {
            ic.endBatchEdit();
        }
        offerUndo(target.text, replacement, target.start, target.editorGeneration);
        finishOperation(getString(R.string.ime_change_applied));
    }

    /** Offers a guarded undo: it only runs if our inserted range is unchanged. */
    private void offerUndo(String original, String inserted, int start, long generation) {
        if (start < 0 || inserted == null) return;
        undoState = new UndoState(original == null ? "" : original, inserted,
                start, start + inserted.length(), generation);
        mainHandler.removeCallbacks(expireUndo);
        mainHandler.postDelayed(expireUndo, UNDO_TIMEOUT_MS);
        if (undoAction != null) undoAction.setVisibility(View.VISIBLE);
    }

    private void undoLastChange() {
        UndoState undo = undoState;
        InputConnection ic = getCurrentInputConnection();
        if (undo == null || ic == null || undo.editorGeneration != editorGeneration
                || !undo.inserted.equals(textInRange(ic, undo.start, undo.end))) {
            clearUndo();
            showStatus(R.string.ime_undo_unavailable);
            return;
        }

        ic.beginBatchEdit();
        boolean selected;
        try {
            selected = ic.setSelection(undo.start, undo.end);
            if (selected) ic.commitText(undo.original, 1);
        } finally {
            ic.endBatchEdit();
        }
        clearUndo();
        showStatus(selected ? R.string.ime_undo_done : R.string.ime_undo_unavailable);
    }

    private void clearUndo() {
        undoState = null;
        if (mainHandler != null) mainHandler.removeCallbacks(expireUndo);
        if (undoAction != null) undoAction.setVisibility(View.GONE);
    }

    private void finishOperation(String message) {
        isRecording = false;
        cancelRequested = false;
        if (inputView != null) inputView.setKeepScreenOn(false);
        if (micLevelView != null) micLevelView.setLevel(0f);
        tintRecordButton(false);
        tintAiMic(false);
        tintEditButton(false);
        if (statusView != null) statusView.setText(message);
        if (hintView != null) hintView.setText("Tap to Record");
        applyMicEnabledState();
    }

    private void showStatus(int stringId) {
        if (statusView != null) statusView.setText(stringId);
    }

    /** Captures both the selected value and its absolute editor range. */
    private SelectionSnapshot captureSelection(InputConnection ic, boolean requireNonEmpty) {
        if (ic == null || !inputActive) return null;
        ExtractedText extracted = ic.getExtractedText(new ExtractedTextRequest(), 0);
        if (extracted == null || extracted.selectionStart < 0 || extracted.selectionEnd < 0) {
            return null;
        }
        int start = extracted.startOffset
                + Math.min(extracted.selectionStart, extracted.selectionEnd);
        int end = extracted.startOffset
                + Math.max(extracted.selectionStart, extracted.selectionEnd);
        CharSequence selected = ic.getSelectedText(0);
        String text = selected == null ? "" : selected.toString();
        if (requireNonEmpty && (start == end || text.isEmpty())) return null;
        return new SelectionSnapshot(text, start, end, editorGeneration);
    }

    private String textInRange(InputConnection ic, int start, int end) {
        ExtractedText extracted = ic.getExtractedText(new ExtractedTextRequest(), 0);
        if (extracted == null || extracted.text == null) return null;
        int localStart = start - extracted.startOffset;
        int localEnd = end - extracted.startOffset;
        if (localStart < 0 || localEnd < localStart || localEnd > extracted.text.length()) {
            return null;
        }
        return extracted.text.subSequence(localStart, localEnd).toString();
    }

    private boolean hasSelection() {
        InputConnection ic = getCurrentInputConnection();
        return captureSelection(ic, true) != null;
    }

    private boolean isSecureEditor() {
        EditorInfo info = getCurrentInputEditorInfo();
        if (info == null) return false;
        int typeClass = info.inputType & InputType.TYPE_MASK_CLASS;
        int variation = info.inputType & InputType.TYPE_MASK_VARIATION;
        if (typeClass == InputType.TYPE_CLASS_NUMBER) {
            return variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD;
        }
        return typeClass == InputType.TYPE_CLASS_TEXT
                && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD);
    }

    private void updateEditSelectionEnabled() {
        if (editSelectionButton == null || editContainer == null) return;
        boolean busy = isRecording || transcriptionPending || postProcessRunning;
        boolean selectionReady = !isSecureEditor() && hasSelection();
        editSelectionButton.setEnabled(!busy);
        editContainer.setAlpha(!busy && selectionReady ? 1.0f : 0.45f);
    }

    private static final class SelectionSnapshot {
        final String text;
        final int start;
        final int end;
        final long editorGeneration;

        SelectionSnapshot(String text, int start, int end, long editorGeneration) {
            this.text = text;
            this.start = start;
            this.end = end;
            this.editorGeneration = editorGeneration;
        }
    }

    private static final class UndoState {
        final String original;
        final String inserted;
        final int start;
        final int end;
        final long editorGeneration;

        UndoState(String original, String inserted, int start, int end,
                  long editorGeneration) {
            this.original = original;
            this.inserted = inserted;
            this.start = start;
            this.end = end;
            this.editorGeneration = editorGeneration;
        }
    }

    /** Rebuilds the configurable row of literal punctuation keys. */
    private void updatePunctuationKeys() {
        if (punctuationRow == null) return;
        punctuationRow.removeAllViews();

        java.util.Set<String> selected = PunctuationPrefs.getSelected(this);
        punctuationRow.setVisibility(selected.isEmpty() ? View.GONE : View.VISIBLE);
        int gap = dp(8);
        int keyHeight = dp(44);
        int index = 0;
        for (String symbol : PunctuationPrefs.AVAILABLE) {
            if (!selected.contains(symbol)) continue;

            TextView key = new TextView(punctuationRow.getContext());
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    0, keyHeight, 1f);
            if (index++ > 0) params.setMarginStart(gap);
            key.setLayoutParams(params);
            key.setGravity(Gravity.CENTER);
            key.setText(symbol);
            key.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            key.setTextColor(MaterialColors.getColor(
                    key, com.google.android.material.R.attr.colorOnSurfaceVariant));
            key.setBackgroundResource(R.drawable.bg_ime_key);
            key.setClickable(true);
            key.setFocusable(true);
            key.setContentDescription(getString(R.string.ime_insert_symbol, symbol));
            key.setOnClickListener(v -> {
                InputConnection ic = getCurrentInputConnection();
                if (ic != null) ic.commitText(symbol, 1);
            });
            punctuationRow.addView(key);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
    public void onAudioLevel(float level) {
        if (micLevelView != null) {
            mainHandler.post(() -> micLevelView.setLevel(level));
        }
    }

    private boolean isPauseAudioEnabled() {
        return new File(getFilesDir(), "pause_audio").exists();
    }

    /** "Record in background" is default ON; the marker file is the opt-out. */
    private boolean isStopOnHideEnabled() {
        return new File(getFilesDir(), "stop_on_hide").exists();
    }
}
