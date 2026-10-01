package dev.notune.transcribe;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.TooltipCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Lists past dictations from {@link HistoryStore}: search, play, copy, share, delete. */
public class HistoryActivity extends AppCompatActivity {

    private static final String TAG = "HistoryActivity";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Adapter adapter = new Adapter();

    private EditText searchField;
    private TextView statusText;
    private TextView emptyText;

    private MediaPlayer player;
    private String playingId;
    private String expandedId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.inflateMenu(R.menu.history);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.history_settings) {
                showSettings();
                return true;
            }
            if (item.getItemId() == R.id.history_clear) {
                confirmClear();
                return true;
            }
            return false;
        });

        searchField = findViewById(R.id.history_search);
        statusText = findViewById(R.id.history_status);
        emptyText = findViewById(R.id.history_empty);

        RecyclerView list = findViewById(R.id.history_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        searchField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { reload(); }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The keyboard adds entries from its own process while we're away.
        reload();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopPlayback();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdown();
    }

    private void reload() {
        String query = searchField.getText() == null ? "" : searchField.getText().toString();
        io.execute(() -> {
            List<HistoryStore.Entry> entries;
            try {
                entries = HistoryStore.list(this, query);
            } catch (RuntimeException e) {
                Log.e(TAG, "Failed to read history", e);
                entries = new ArrayList<>();
            }
            List<HistoryStore.Entry> result = entries;
            main.post(() -> show(result, query));
        });
    }

    private void show(List<HistoryStore.Entry> entries, String query) {
        adapter.setEntries(entries);
        boolean enabled = HistoryStore.isEnabled(this);
        statusText.setText(!enabled ? getString(R.string.history_off)
                : HistoryStore.isAudioEnabled(this)
                        ? HistoryStore.getAudioDays(this) <= HistoryStore.KEEP_FOREVER
                                ? getString(R.string.history_status_forever)
                                : getString(R.string.history_status_audio, HistoryStore.getAudioDays(this))
                        : getString(R.string.history_status_no_audio));
        emptyText.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
        emptyText.setText(query.trim().isEmpty()
                ? R.string.history_empty : R.string.history_no_results);
    }

    // --- Actions --------------------------------------------------------------

    private void copy(HistoryStore.Entry e) {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.transcript_clip_label), e.finalText));
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, R.string.history_copied, Toast.LENGTH_SHORT).show();
        }
    }

    private void share(HistoryStore.Entry e) {
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, e.finalText);
        startActivity(Intent.createChooser(send, null));
    }

    private void delete(HistoryStore.Entry e) {
        if (e.id.equals(playingId)) stopPlayback();
        io.execute(() -> {
            HistoryStore.delete(this, e);
            main.post(this::reload);
        });
    }

    private void togglePlayback(HistoryStore.Entry e) {
        if (e.id.equals(playingId)) {
            stopPlayback();
            return;
        }
        stopPlayback();
        File audio = HistoryStore.audioFile(this, e);
        if (audio == null || !audio.exists()) return;
        MediaPlayer mp = new MediaPlayer();
        try {
            mp.setDataSource(audio.getAbsolutePath());
            mp.setOnCompletionListener(m -> stopPlayback());
            mp.prepare();
            mp.start();
        } catch (IOException ex) {
            Log.e(TAG, "Can't play " + audio, ex);
            mp.release();
            Toast.makeText(this, R.string.history_play_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        player = mp;
        playingId = e.id;
        adapter.notifyDataSetChanged();
    }

    private void stopPlayback() {
        if (player != null) {
            player.release();
            player = null;
        }
        if (playingId != null) {
            playingId = null;
            adapter.notifyDataSetChanged();
        }
    }

    private void confirmClear() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.history_clear_title)
                .setMessage(R.string.history_clear_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.history_clear, (d, w) -> {
                    stopPlayback();
                    io.execute(() -> {
                        HistoryStore.clear(this);
                        main.post(this::reload);
                    });
                })
                .show();
    }

    private void showSettings() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_history_settings, null);
        CompoundButton enabled = view.findViewById(R.id.history_enabled);
        CompoundButton audio = view.findViewById(R.id.history_audio);
        RadioGroup days = view.findViewById(R.id.history_audio_days);
        TextView storage = view.findViewById(R.id.history_storage);

        enabled.setChecked(HistoryStore.isEnabled(this));
        audio.setChecked(HistoryStore.isAudioSettingOn(this));
        audio.setEnabled(enabled.isChecked());
        int d = HistoryStore.getAudioDays(this);
        days.check(d <= HistoryStore.KEEP_FOREVER ? R.id.history_days_forever
                : d == 1 ? R.id.history_days_1 : d >= 30 ? R.id.history_days_30 : R.id.history_days_7);
        io.execute(() -> {
            long bytes = HistoryStore.audioBytes(this);
            main.post(() -> storage.setText(getString(R.string.history_storage,
                    Formatter.formatShortFileSize(this, bytes))));
        });

        enabled.setOnCheckedChangeListener((b, on) -> audio.setEnabled(on));

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.history_settings)
                .setView(view)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    HistoryStore.setEnabled(this, enabled.isChecked());
                    HistoryStore.setAudioEnabled(this, audio.isChecked());
                    int checked = days.getCheckedRadioButtonId();
                    HistoryStore.setAudioDays(this,
                            checked == R.id.history_days_forever ? HistoryStore.KEEP_FOREVER
                            : checked == R.id.history_days_1 ? 1
                            : checked == R.id.history_days_30 ? 30 : 7);
                    io.execute(() -> {
                        HistoryStore.prune(this);
                        main.post(this::reload);
                    });
                })
                .show();
    }

    // --- List -----------------------------------------------------------------

    private String sourceLabel(String source) {
        switch (source) {
            case HistoryStore.SOURCE_AI: return getString(R.string.history_source_ai);
            case HistoryStore.SOURCE_EDIT: return getString(R.string.history_source_edit);
            case HistoryStore.SOURCE_OTHER_KEYBOARD: return getString(R.string.history_source_other);
            default: return getString(R.string.history_source_dictation);
        }
    }

    /** Null for the normal case (text inserted), so only exceptions stand out. */
    private String outcomeLabel(String outcome) {
        switch (outcome) {
            case HistoryStore.OUTCOME_CLIPBOARD: return getString(R.string.history_outcome_clipboard);
            case HistoryStore.OUTCOME_KEPT: return getString(R.string.history_outcome_kept);
            case HistoryStore.OUTCOME_UNCHANGED: return getString(R.string.history_outcome_unchanged);
            case HistoryStore.OUTCOME_FAILED: return getString(R.string.history_outcome_failed);
            case HistoryStore.OUTCOME_CANCELED: return getString(R.string.history_outcome_canceled);
            case HistoryStore.OUTCOME_PENDING: return getString(R.string.history_outcome_pending);
            default: return null;
        }
    }

    private class Adapter extends RecyclerView.Adapter<Holder> {
        private List<HistoryStore.Entry> entries = new ArrayList<>();

        void setEntries(List<HistoryStore.Entry> entries) {
            this.entries = entries;
            notifyDataSetChanged();
        }

        @Override
        public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_history, parent, false));
        }

        @Override
        public void onBindViewHolder(Holder h, int position) {
            h.bind(entries.get(position));
        }

        @Override
        public int getItemCount() {
            return entries.size();
        }
    }

    private class Holder extends RecyclerView.ViewHolder {
        final TextView meta;
        final TextView text;
        final TextView details;
        final View actions;
        final MaterialButton play;

        Holder(View v) {
            super(v);
            meta = v.findViewById(R.id.history_meta);
            text = v.findViewById(R.id.history_text);
            details = v.findViewById(R.id.history_details);
            actions = v.findViewById(R.id.history_actions);
            play = v.findViewById(R.id.history_play);
            for (int id : new int[]{R.id.history_play, R.id.history_copy,
                    R.id.history_share, R.id.history_delete}) {
                View b = v.findViewById(id);
                TooltipCompat.setTooltipText(b, b.getContentDescription());
            }
        }

        void bind(HistoryStore.Entry e) {
            StringBuilder m = new StringBuilder(DateUtils.formatDateTime(itemView.getContext(),
                    e.created, DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME
                            | DateUtils.FORMAT_ABBREV_MONTH));
            m.append(" · ").append(sourceLabel(e.source));
            if (e.durationMs > 0) {
                m.append(" · ").append(DateUtils.formatElapsedTime(Math.max(1, e.durationMs / 1000)));
            }
            String outcome = outcomeLabel(e.outcome);
            if (outcome != null) m.append(" · ").append(outcome);
            meta.setText(m);

            text.setText(e.finalText);
            boolean expanded = e.id.equals(expandedId);
            text.setMaxLines(expanded ? Integer.MAX_VALUE : 3);
            // Expanded text can be selected to copy just part of it. Collapsed
            // it stays plain so a tap anywhere on the card expands it.
            text.setTextIsSelectable(expanded);
            details.setTextIsSelectable(expanded);

            StringBuilder d = new StringBuilder();
            if (e.originalText != null) {
                d.append(getString(R.string.history_detail_original, e.originalText));
            }
            if (e.rawText != null && !e.rawText.equals(e.finalText)) {
                if (d.length() > 0) d.append("\n\n");
                d.append(getString(HistoryStore.SOURCE_EDIT.equals(e.source)
                        ? R.string.history_detail_instruction : R.string.history_detail_raw, e.rawText));
            }
            if (e.note != null) {
                if (d.length() > 0) d.append("\n\n");
                d.append(e.note);
            }
            details.setText(d);
            details.setVisibility(expanded && d.length() > 0 ? View.VISIBLE : View.GONE);
            actions.setVisibility(expanded ? View.VISIBLE : View.GONE);

            File audio = HistoryStore.audioFile(itemView.getContext(), e);
            play.setVisibility(audio != null && audio.exists() ? View.VISIBLE : View.GONE);
            boolean playing = e.id.equals(playingId);
            play.setIconResource(playing ? R.drawable.ic_stop : R.drawable.ic_play);
            play.setContentDescription(getString(playing ? R.string.history_stop : R.string.history_play));
            TooltipCompat.setTooltipText(play, play.getContentDescription());

            View.OnClickListener toggle = v -> {
                expandedId = expanded ? null : e.id;
                adapter.notifyDataSetChanged();
            };
            itemView.setOnClickListener(toggle);
            // Selectable text consumes taps; the date line still collapses.
            meta.setOnClickListener(toggle);
            play.setOnClickListener(v -> togglePlayback(e));
            itemView.findViewById(R.id.history_copy).setOnClickListener(v -> copy(e));
            itemView.findViewById(R.id.history_share).setOnClickListener(v -> share(e));
            itemView.findViewById(R.id.history_delete).setOnClickListener(v -> delete(e));
        }
    }
}
