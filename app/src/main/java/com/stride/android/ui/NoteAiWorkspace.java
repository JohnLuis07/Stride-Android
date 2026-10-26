package com.stride.android.ui;

import android.app.AlertDialog;
import android.app.Dialog;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.stride.android.R;
import com.stride.android.adapter.NoteAiAdapters;
import com.stride.android.databinding.DialogNoteAiBinding;
import com.stride.android.model.Note;
import com.stride.android.model.NoteAiState;
import com.stride.android.utils.SupabaseApi;
import com.stride.android.viewmodel.NoteAiViewModel;

/** Source selection, grounded chat, and persistent study outputs for one saved note. */
final class NoteAiWorkspace {
    private final ComponentActivity activity;
    private final Note note;
    private final NoteAiViewModel model;
    private final DialogNoteAiBinding binding;
    private final Dialog dialog;
    private final ActivityResultLauncher<String[]> picker;
    private final Observer<NoteAiState> observer = this::render;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final NoteAiAdapters.Sources sources;
    private final NoteAiAdapters.Messages messages;
    private final NoteAiAdapters.Artifacts artifacts;
    private final TextWatcher questionWatcher;
    private AlertDialog confirmation;
    private NoteAiStudyDialog study;
    private NoteAiPdfViewer pdf;
    private boolean closed;
    private int messageCount;
    private final Runnable poll = this::pollSources;

    private void pollSources() { if (!closed) model.reload(); }

    NoteAiWorkspace(ComponentActivity activity, SupabaseApi api, String profileId, Note note,
            Runnable dismissed, Runnable edit) {
        this.activity = activity; this.note = note;
        model = new ViewModelProvider(activity).get("note-ai:" + profileId + ":" + note.id, NoteAiViewModel.class);
        model.configure(api, note);
        binding = DialogNoteAiBinding.inflate(activity.getLayoutInflater());
        binding.notebookTitle.setText(note.title.trim().isEmpty() ? activity.getString(R.string.notes_untitled) : note.title);
        // Dynamic registration is intentional: this workspace can open after the Activity is STARTED.
        picker = activity.getActivityResultRegistry().register("note-ai-upload:" + profileId + ":" + note.id,
                new ActivityResultContracts.OpenDocument(), uri -> { if (!closed && uri != null) model.upload(uri); });
        sources = new NoteAiAdapters.Sources(new NoteAiAdapters.SourceActions() {
            @Override public void select(NoteAiState.Source source, boolean selected) { model.select(source, selected); }
            @Override public void preview(NoteAiState.Source source) { openPdf(source.id, source.title, 1); }
            @Override public void retry(NoteAiState.Source source) { model.index(source); }
            @Override public void remove(NoteAiState.Source source) { confirmRemove(source); }
        });
        messages = new NoteAiAdapters.Messages(this::citation);
        artifacts = new NoteAiAdapters.Artifacts(this::openStudy);
        binding.sourcesList.setLayoutManager(new LinearLayoutManager(activity));
        binding.sourcesList.setAdapter(sources);
        binding.chatList.setLayoutManager(new LinearLayoutManager(activity));
        binding.chatList.setAdapter(messages);
        binding.artifactsList.setLayoutManager(new LinearLayoutManager(activity));
        binding.artifactsList.setAdapter(artifacts);
        binding.sourcesList.setItemAnimator(null);
        binding.chatList.setItemAnimator(null);
        binding.artifactsList.setItemAnimator(null);
        questionWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { model.question(text.toString()); }
            @Override public void afterTextChanged(Editable text) { }
        };
        binding.questionInput.addTextChangedListener(questionWatcher);
        dialog = NoteAiDialogs.create(activity, binding, () -> {
            closed = true;
            model.state().removeObserver(observer);
            main.removeCallbacksAndMessages(null);
            picker.unregister();
            binding.questionInput.removeTextChangedListener(questionWatcher);
            binding.sourcesList.setAdapter(null); binding.chatList.setAdapter(null); binding.artifactsList.setAdapter(null);
            if (confirmation != null) confirmation.dismiss();
            if (study != null) study.close();
            if (pdf != null) pdf.close();
            dismissed.run();
        });
        binding.closeButton.setOnClickListener(v -> close());
        binding.editButton.setOnClickListener(v -> { close(); edit.run(); });
        binding.attachButton.setOnClickListener(v -> choosePdf());
        binding.sourcesTab.setOnClickListener(v -> model.tab("sources"));
        binding.chatTab.setOnClickListener(v -> model.tab("chat"));
        binding.studyTab.setOnClickListener(v -> model.tab("study"));
        binding.refreshButton.setOnClickListener(v -> model.reload());
        binding.sendButton.setOnClickListener(v -> model.generate("chat"));
        binding.summaryButton.setOnClickListener(v -> model.generate("summary"));
        binding.guideButton.setOnClickListener(v -> model.generate("study_guide"));
        binding.cardsButton.setOnClickListener(v -> model.generate("flashcards"));
        binding.quizButton.setOnClickListener(v -> model.generate("quiz"));
        model.state().observe(activity, observer);
    }
    void show() { NoteAiDialogs.show(dialog, binding); model.reload(); }
    void close() { dialog.dismiss(); }

    private void render(NoteAiState state) {
        if (closed) return;
        boolean blocked = state.busy || state.loading;
        binding.selectionText.setText(activity.getString(R.string.note_ai_selection, state.selectedSources.size()));
        binding.sourcesPanel.setVisibility("sources".equals(state.tab) ? View.VISIBLE : View.GONE);
        binding.chatPanel.setVisibility("chat".equals(state.tab) ? View.VISIBLE : View.GONE);
        binding.studyPanel.setVisibility("study".equals(state.tab) ? View.VISIBLE : View.GONE);
        tabAppearance(binding.sourcesTab, "sources".equals(state.tab));
        tabAppearance(binding.chatTab, "chat".equals(state.tab));
        tabAppearance(binding.studyTab, "study".equals(state.tab));
        binding.progress.setVisibility(blocked ? View.VISIBLE : View.GONE);
        binding.feedback.setVisibility(state.errorResource != 0 || blocked ? View.VISIBLE : View.GONE);
        binding.feedback.setText(state.errorResource != 0 ? state.errorResource : state.progressResource != 0 ? state.progressResource : R.string.notes_loading);
        binding.feedback.setTextColor(ContextCompat.getColor(activity, state.errorResource != 0 ? R.color.notes_error : R.color.notes_muted));
        binding.refreshButton.setEnabled(!blocked);
        binding.attachButton.setEnabled(!blocked);
        binding.editButton.setEnabled(!blocked);
        binding.sendButton.setEnabled(!blocked);
        binding.questionInput.setEnabled(!state.busy);
        binding.summaryButton.setEnabled(!blocked); binding.guideButton.setEnabled(!blocked);
        binding.cardsButton.setEnabled(!blocked); binding.quizButton.setEnabled(!blocked);
        if (!binding.questionInput.getText().toString().equals(state.question)) {
            binding.questionInput.setText(state.question);
            binding.questionInput.setSelection(binding.questionInput.length());
        }
        sources.update(state); messages.submit(state.messages); artifacts.submit(state.artifacts);
        binding.sourcesEmpty.setVisibility(state.sources.isEmpty() && !state.loading ? View.VISIBLE : View.GONE);
        binding.chatEmpty.setVisibility(state.messages.isEmpty() ? View.VISIBLE : View.GONE);
        binding.studyEmpty.setVisibility(state.artifacts.isEmpty() ? View.VISIBLE : View.GONE);
        if (state.messages.size() > messageCount) binding.chatList.scrollToPosition(state.messages.size() - 1);
        messageCount = state.messages.size();
        main.removeCallbacks(poll);
        if (!blocked && state.errorResource == 0) for (NoteAiState.Source source : state.sources) {
            if ("indexing".equals(source.status)) { main.postDelayed(poll, 6000); break; }
        }
    }

    private void choosePdf() {
        if (confirmation != null) return;
        confirmation = new AlertDialog.Builder(activity).setTitle(R.string.note_ai_upload_title)
                .setMessage(R.string.note_ai_upload_consent).setNegativeButton(R.string.notes_cancel, null)
                .setPositiveButton(R.string.note_ai_continue, (value, which) -> {
                    try { picker.launch(new String[]{"application/pdf"}); }
                    catch (RuntimeException failure) { Toast.makeText(activity, R.string.note_ai_pdf_unavailable, Toast.LENGTH_LONG).show(); }
                }).create();
        confirmation.setOnDismissListener(value -> confirmation = null);
        confirmation.show();
    }
    private void tabAppearance(android.widget.Button button, boolean selected) {
        button.setSelected(selected);
        button.setTextColor(ContextCompat.getColor(activity, selected ? android.R.color.white : R.color.notes_muted));
    }
    private void confirmRemove(NoteAiState.Source source) {
        if (confirmation != null) return;
        confirmation = new AlertDialog.Builder(activity).setTitle(R.string.note_ai_remove_title)
                .setMessage(R.string.note_ai_remove_message).setNegativeButton(R.string.notes_cancel, null)
                .setPositiveButton(R.string.note_ai_remove, (value, which) -> model.delete(source)).create();
        confirmation.setOnDismissListener(value -> confirmation = null);
        confirmation.show();
    }
    private void citation(NoteAiState.Citation citation) { openPdf(citation.sourceId, citation.title, citation.page); }
    private void openPdf(String sourceId, String title, int page) {
        if (closed || pdf != null) return;
        model.preview(sourceId, (file, error) -> {
            if (closed) { if (file != null) file.delete(); return; }
            if (file == null || error != null) return;
            pdf = new NoteAiPdfViewer(activity, file, title, page, () -> pdf = null);
            pdf.show();
        });
    }
    private void openStudy(NoteAiState.Artifact artifact) {
        if (closed || study != null) return;
        study = new NoteAiStudyDialog(activity, artifact, model, this::citation, () -> study = null);
        study.show();
    }
}
