package com.stride.android.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.stride.android.R;
import com.stride.android.databinding.ItemNoteAiArtifactBinding;
import com.stride.android.databinding.ItemNoteAiCitationBinding;
import com.stride.android.databinding.ItemNoteAiMessageBinding;
import com.stride.android.databinding.ItemNoteAiQuizOptionBinding;
import com.stride.android.databinding.ItemNoteAiSourceBinding;
import com.stride.android.model.NoteAiState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/** Binding-backed recycled rows for the workspace and study dialogs. */
public final class NoteAiAdapters {
    private NoteAiAdapters() { }

    private interface Inflate<B extends ViewBinding> { B create(LayoutInflater inflater, ViewGroup parent); }
    public static final class Holder<B extends ViewBinding> extends RecyclerView.ViewHolder {
        final B binding;
        Holder(B binding) { super(binding.getRoot()); this.binding = binding; }
    }
    public abstract static class Rows<T, B extends ViewBinding> extends RecyclerView.Adapter<Holder<B>> {
        final List<T> items = new ArrayList<>();
        private final Inflate<B> inflater;
        Rows(Inflate<B> inflater) { this.inflater = inflater; }
        public void submit(List<T> values) {
            items.clear(); items.addAll(values); notifyDataSetChanged();
        }
        @NonNull @Override public Holder<B> onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            return new Holder<>(inflater.create(LayoutInflater.from(parent.getContext()), parent));
        }
        @Override public int getItemCount() { return items.size(); }
        @Override public void onBindViewHolder(@NonNull Holder<B> holder, int position) { bind(holder.binding, items.get(position)); }
        abstract void bind(B binding, T value);
    }

    public interface SourceActions {
        void select(NoteAiState.Source source, boolean selected);
        void preview(NoteAiState.Source source);
        void retry(NoteAiState.Source source);
        void remove(NoteAiState.Source source);
    }
    public static final class Sources extends Rows<NoteAiState.Source, ItemNoteAiSourceBinding> {
        private final SourceActions actions;
        private Set<String> selected = Collections.emptySet();
        private boolean busy;
        public Sources(SourceActions actions) {
            super((inflater, parent) -> ItemNoteAiSourceBinding.inflate(inflater, parent, false)); this.actions = actions;
        }
        public void update(NoteAiState state) { selected = state.selectedSources; busy = state.busy || state.loading; submit(state.sources); }
        @Override void bind(ItemNoteAiSourceBinding b, NoteAiState.Source source) {
            Context context = b.getRoot().getContext();
            b.sourceTitle.setText(source.title);
            b.sourceMeta.setText(context.getString(R.string.note_ai_source_meta, source.pages,
                    String.format(Locale.getDefault(), "%.1f", source.bytes / 1048576.0)));
            int status = source.ready() ? R.string.note_ai_ready : "indexing".equals(source.status) ? R.string.note_ai_indexing
                    : "failed".equals(source.status) ? R.string.note_ai_failed : "deleting".equals(source.status)
                    ? R.string.note_ai_deleting : R.string.note_ai_pending;
            b.sourceStatus.setText(status);
            b.sourceStatus.setTextColor(ContextCompat.getColor(context, source.ready() ? R.color.notes_success
                    : "failed".equals(source.status) ? R.color.notes_error : R.color.notes_muted));
            b.selected.setOnCheckedChangeListener(null);
            b.selected.setChecked(selected.contains(source.id));
            b.selected.setEnabled(!busy && source.ready());
            b.selected.setContentDescription(context.getString(R.string.note_ai_select, source.title));
            b.selected.setOnCheckedChangeListener((button, checked) -> actions.select(source, checked));
            b.previewButton.setEnabled(!busy && !"pending".equals(source.status) && !"deleting".equals(source.status));
            b.previewButton.setOnClickListener(v -> actions.preview(source));
            b.retryButton.setVisibility("failed".equals(source.status) || "pending".equals(source.status) ? View.VISIBLE : View.GONE);
            b.retryButton.setEnabled(!busy);
            b.retryButton.setOnClickListener(v -> actions.retry(source));
            b.removeButton.setEnabled(!busy);
            b.removeButton.setOnClickListener(v -> actions.remove(source));
        }
    }

    public static final class Citations extends Rows<NoteAiState.Citation, ItemNoteAiCitationBinding> {
        private final Consumer<NoteAiState.Citation> open;
        public Citations(Consumer<NoteAiState.Citation> open) {
            super((inflater, parent) -> ItemNoteAiCitationBinding.inflate(inflater, parent, false)); this.open = open;
        }
        @Override void bind(ItemNoteAiCitationBinding b, NoteAiState.Citation value) {
            b.getRoot().setText(value.page > 0 ? b.getRoot().getContext().getString(R.string.note_ai_reference_page, value.title, value.page) : value.title);
            b.getRoot().setOnClickListener(v -> open.accept(value));
        }
    }

    public static final class Messages extends Rows<NoteAiState.Message, ItemNoteAiMessageBinding> {
        private final Consumer<NoteAiState.Citation> open;
        public Messages(Consumer<NoteAiState.Citation> open) {
            super((inflater, parent) -> ItemNoteAiMessageBinding.inflate(inflater, parent, false)); this.open = open;
        }
        @Override void bind(ItemNoteAiMessageBinding b, NoteAiState.Message value) {
            b.question.setText(value.question); b.answer.setText(value.answer);
            b.noCitations.setVisibility(value.citations.isEmpty() ? View.VISIBLE : View.GONE);
            Citations adapter = new Citations(open);
            b.citationsList.setLayoutManager(new LinearLayoutManager(b.getRoot().getContext(), RecyclerView.HORIZONTAL, false));
            b.citationsList.setAdapter(adapter); adapter.submit(value.citations);
        }
    }

    public static int kindLabel(String kind) {
        switch (kind) {
            case "flashcards": return R.string.note_ai_flashcards;
            case "quiz": return R.string.note_ai_quiz;
            case "study_guide": return R.string.note_ai_guide;
            default: return R.string.note_ai_summary;
        }
    }
    public static final class Artifacts extends Rows<NoteAiState.Artifact, ItemNoteAiArtifactBinding> {
        private final Consumer<NoteAiState.Artifact> open;
        public Artifacts(Consumer<NoteAiState.Artifact> open) {
            super((inflater, parent) -> ItemNoteAiArtifactBinding.inflate(inflater, parent, false)); this.open = open;
        }
        @Override void bind(ItemNoteAiArtifactBinding b, NoteAiState.Artifact value) {
            b.artifactTitle.setText(value.title); b.artifactKind.setText(kindLabel(value.kind));
            b.openButton.setOnClickListener(v -> open.accept(value));
            b.getRoot().setOnClickListener(v -> open.accept(value));
        }
    }

    public static final class QuizOptions extends Rows<String, ItemNoteAiQuizOptionBinding> {
        private final Consumer<Integer> select;
        private int chosen = -1, correct = -1;
        public QuizOptions(Consumer<Integer> select) {
            super((inflater, parent) -> ItemNoteAiQuizOptionBinding.inflate(inflater, parent, false)); this.select = select;
        }
        public void update(List<String> options, int chosen, int correct) {
            this.chosen = chosen; this.correct = correct; submit(options);
        }
        @Override void bind(ItemNoteAiQuizOptionBinding b, String value) {
            int position = items.indexOf(value);
            // The holder position is supplied by the overridden binder below, including duplicate options.
            bindOption(b, value, position);
        }
        @Override public void onBindViewHolder(@NonNull Holder<ItemNoteAiQuizOptionBinding> holder, int position) {
            bindOption(holder.binding, items.get(position), position);
        }
        private void bindOption(ItemNoteAiQuizOptionBinding b, String value, int position) {
            b.getRoot().setText((position + 1) + ". " + value);
            boolean answered = chosen >= 0;
            b.getRoot().setTextColor(ContextCompat.getColor(b.getRoot().getContext(), answered && position == correct
                    ? R.color.notes_success : answered && position == chosen ? R.color.notes_error : R.color.notes_ink));
            b.getRoot().setAlpha(answered && position != chosen && position != correct ? 0.6f : 1f);
            b.getRoot().setOnClickListener(v -> { if (!answered) select.accept(position); });
        }
    }
}
