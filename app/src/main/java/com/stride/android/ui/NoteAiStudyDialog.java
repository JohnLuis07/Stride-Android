package com.stride.android.ui;

import android.app.Dialog;
import android.view.View;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.lifecycle.Observer;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.stride.android.R;
import com.stride.android.adapter.NoteAiAdapters;
import com.stride.android.databinding.DialogNoteAiStudyBinding;
import com.stride.android.model.NoteAiState;
import com.stride.android.viewmodel.NoteAiViewModel;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

final class NoteAiStudyDialog {
    private final ComponentActivity activity;
    private final NoteAiState.Artifact artifact;
    private final NoteAiViewModel model;
    private final NoteAiViewModel.StudyProgress progress;
    private final DialogNoteAiStudyBinding binding;
    private final NoteAiAdapters.QuizOptions options;
    private final Dialog dialog;
    private final Observer<NoteAiState> observer;
    private boolean closed;
    private boolean saved;

    NoteAiStudyDialog(ComponentActivity activity, NoteAiState.Artifact artifact, NoteAiViewModel model,
            Consumer<NoteAiState.Citation> open, Runnable dismissed) {
        this.activity = activity; this.artifact = artifact; this.model = model;
        progress = model.studyProgress(artifact.id);
        saved = model.wasSaved(artifact.id);
        binding = DialogNoteAiStudyBinding.inflate(activity.getLayoutInflater());
        binding.studyTitle.setText(artifact.title);
        options = new NoteAiAdapters.QuizOptions(index -> { progress.answers.put(progress.index, index); render(); });
        binding.optionsList.setLayoutManager(new LinearLayoutManager(activity));
        binding.optionsList.setAdapter(options);
        NoteAiAdapters.Citations citations = new NoteAiAdapters.Citations(open);
        binding.citationsList.setLayoutManager(new LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false));
        binding.citationsList.setAdapter(citations); citations.submit(artifact.citations);
        binding.noCitations.setVisibility(artifact.citations.isEmpty() ? View.VISIBLE : View.GONE);
        observer = state -> {
            binding.saveButton.setEnabled(!state.busy && !state.loading && !saved);
            binding.feedbackText.setVisibility(state.busy || state.errorResource != 0 ? View.VISIBLE : View.GONE);
            binding.feedbackText.setText(state.errorResource != 0 ? state.errorResource
                    : state.progressResource != 0 ? state.progressResource : R.string.note_ai_working);
            binding.feedbackText.setTextColor(androidx.core.content.ContextCompat.getColor(activity,
                    state.errorResource != 0 ? R.color.notes_error : R.color.notes_muted));
        };
        dialog = NoteAiDialogs.create(activity, binding, () -> {
            closed = true;
            model.state().removeObserver(observer);
            binding.optionsList.setAdapter(null); binding.citationsList.setAdapter(null);
            dismissed.run();
        });
        binding.closeButton.setOnClickListener(v -> close());
        binding.previousButton.setOnClickListener(v -> { progress.index--; render(); });
        binding.nextButton.setOnClickListener(v -> { progress.index++; render(); });
        binding.revealButton.setOnClickListener(v -> { progress.revealed.add(progress.index); render(); });
        binding.saveButton.setOnClickListener(v -> model.save(artifact, success -> {
            if (closed) return;
            saved = success;
            binding.saveButton.setEnabled(!saved);
            Toast.makeText(activity, success ? R.string.note_ai_saved : R.string.note_ai_request_failed, Toast.LENGTH_LONG).show();
        }));
        model.state().observe(activity, observer);
        render();
    }
    void show() { NoteAiDialogs.show(dialog, binding); }
    void close() { dialog.dismiss(); }
    private void render() {
        boolean cards = "flashcards".equals(artifact.kind), quiz = "quiz".equals(artifact.kind);
        JSONArray values = artifact.data.optJSONArray(cards ? "cards" : "questions");
        int count = values == null ? 0 : values.length();
        boolean steps = (cards || quiz) && count > 0;
        progress.index = Math.max(0, Math.min(count - 1, progress.index));
        binding.counter.setVisibility(steps ? View.VISIBLE : View.GONE);
        binding.previousButton.setVisibility(steps ? View.VISIBLE : View.GONE);
        binding.nextButton.setVisibility(steps ? View.VISIBLE : View.GONE);
        binding.previousButton.setEnabled(progress.index > 0);
        binding.nextButton.setEnabled(progress.index + 1 < count);
        binding.optionsList.setVisibility(quiz && steps ? View.VISIBLE : View.GONE);
        binding.revealButton.setVisibility(cards && steps && !progress.revealed.contains(progress.index) ? View.VISIBLE : View.GONE);
        binding.score.setVisibility(quiz && steps ? View.VISIBLE : View.GONE);
        binding.explanation.setVisibility(View.GONE);
        if (!steps) { binding.studyContent.setText(artifact.content); return; }
        binding.counter.setText(activity.getString(R.string.note_ai_counter, progress.index + 1, count));
        JSONObject value = values.optJSONObject(progress.index);
        if (value == null) return;
        binding.studyContent.setText(value.optString(cards ? "front" : "question"));
        if (cards && progress.revealed.contains(progress.index)) {
            binding.explanation.setVisibility(View.VISIBLE); binding.explanation.setText(value.optString("back"));
        }
        if (quiz) {
            List<String> labels = new ArrayList<>();
            JSONArray choices = value.optJSONArray("options");
            if (choices != null) for (int i = 0; i < choices.length(); i++) labels.add(choices.optString(i));
            Integer chosen = progress.answers.get(progress.index);
            int answer = value.optInt("answer", -1);
            options.update(labels, chosen == null ? -1 : chosen, answer);
            if (chosen != null) {
                binding.explanation.setVisibility(View.VISIBLE);
                binding.explanation.setText(activity.getString(R.string.note_ai_answer,
                        answer >= 0 && answer < labels.size() ? labels.get(answer) : "") + "\n\n" + value.optString("explanation"));
            }
            int correct = 0;
            for (int i = 0; i < count; i++) {
                JSONObject question = values.optJSONObject(i);
                Integer picked = progress.answers.get(i);
                if (question != null && picked != null && picked == question.optInt("answer", -1)) correct++;
            }
            binding.score.setText(activity.getString(R.string.note_ai_score, correct, progress.answers.size()));
        }
    }
}
