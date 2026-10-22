package com.stride.android.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.stride.android.R;
import com.stride.android.adapter.NotesAdapter;
import com.stride.android.databinding.ScreenNotesBinding;
import com.stride.android.model.Note;
import com.stride.android.model.NotesState;
import com.stride.android.utils.SupabaseApi;
import com.stride.android.viewmodel.NotesViewModel;

import org.json.JSONObject;

/** Searchable notebook with a directly bound header and recycled note cards. */
public final class NotesScreen implements FeatureScreen {
    private final ComponentActivity activity;
    private final ScreenNotesBinding binding;
    private final NotesViewModel viewModel;
    private final Observer<NotesState> observer = this::render;
    private final TextWatcher searchWatcher = new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence text, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence text, int start, int before, int count) {
            viewModel.search(text.toString());
        }

        @Override
        public void afterTextChanged(Editable text) {
        }
    };
    private NotesAdapter adapter;
    private NoteEditor editor;
    private AlertDialog deleteConfirmation;
    private boolean closed;

    public NotesScreen(ComponentActivity activity, SupabaseApi api, JSONObject user) {
        this.activity = activity;
        binding = ScreenNotesBinding.inflate(activity.getLayoutInflater());
        viewModel = new ViewModelProvider(activity).get(
                "notes:" + user.optString("id"), NotesViewModel.class);
        viewModel.configure(api, user.optString("id"));
    }

    @Override
    public View root() {
        return binding.getRoot();
    }

    public void bind() {
        adapter = new NotesAdapter(new NotesAdapter.Actions() {
            @Override
            public void onOpen(Note note) {
                showEditor(note);
            }

            @Override
            public void onDelete(Note note) {
                confirmDelete(note);
            }
        });
        binding.notesList.setLayoutManager(new LinearLayoutManager(activity));
        binding.notesList.setItemAnimator(null);
        binding.notesList.setAdapter(adapter);
        NotesState state = viewModel.state().getValue();
        binding.searchInput.setText(state == null ? "" : state.query);
        binding.searchInput.addTextChangedListener(searchWatcher);
        binding.searchInput.setOnEditorActionListener((view, action, event) -> {
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard();
                return true;
            }
            return false;
        });
        binding.addButton.setOnClickListener(v -> showEditor(null));
        binding.refreshButton.setOnClickListener(v -> viewModel.reload());
        viewModel.state().observe(activity, observer);
        viewModel.reload();
        if (viewModel.hasDraft()) {
            showEditor(null);
        }
    }

    private void render(NotesState state) {
        if (closed) {
            return;
        }
        adapter.setBusy(state.saving || state.loading);
        adapter.submitList(state.notes);
        binding.addButton.setEnabled(!state.saving && !state.loading);
        binding.refreshButton.setEnabled(!state.loading && !state.saving);
        binding.refreshButton.setText(state.loadFailed ? R.string.notes_retry : R.string.notes_refresh);
        binding.resultCount.setText(activity.getString(R.string.notes_results, state.notes.size(), state.total));
        binding.loadingProgress.setVisibility(state.loading || state.saving ? View.VISIBLE : View.GONE);
        binding.feedbackText.setVisibility(state.loading || state.saving || state.loadFailed ? View.VISIBLE : View.GONE);
        binding.feedbackText.setText(state.saving ? R.string.notes_saving
                : state.loading ? R.string.notes_loading : R.string.notes_load_failed);
        binding.feedbackText.setTextColor(ContextCompat.getColor(activity,
                state.loadFailed ? R.color.notes_error : R.color.notes_muted));
        boolean empty = !state.loading && !state.loadFailed && !state.saving && state.notes.isEmpty();
        binding.emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        binding.notesList.setVisibility(empty ? View.GONE : View.VISIBLE);
        boolean searching = !state.query.trim().isEmpty();
        binding.emptyTitle.setText(searching ? R.string.notes_no_results_title : R.string.notes_empty_title);
        binding.emptyMessage.setText(searching ? R.string.notes_no_results_message : R.string.notes_empty_message);
    }

    private void showEditor(Note note) {
        if (closed || editor != null) {
            return;
        }
        if (viewModel.isSaving() && !viewModel.hasDraft()) {
            return;
        }
        hideKeyboard();
        viewModel.beginEditing(note);
        editor = new NoteEditor(activity, viewModel, () -> editor = null);
        editor.show();
    }

    private void confirmDelete(Note note) {
        NotesState state = viewModel.state().getValue();
        if (closed || state == null || state.saving || state.loading || deleteConfirmation != null) {
            return;
        }
        String title = note.title.trim().isEmpty() ? activity.getString(R.string.notes_untitled) : note.title;
        deleteConfirmation = new AlertDialog.Builder(activity)
                .setTitle(R.string.notes_delete_title)
                .setMessage(activity.getString(R.string.notes_delete_message, title))
                .setNegativeButton(R.string.notes_cancel, null)
                .setPositiveButton(R.string.notes_delete, (dialog, which) -> viewModel.delete(note, success -> {
                    if (!closed && !success) {
                        Toast.makeText(activity, R.string.notes_delete_failed, Toast.LENGTH_LONG).show();
                    }
                }))
                .create();
        deleteConfirmation.setOnDismissListener(dialog -> deleteConfirmation = null);
        deleteConfirmation.show();
    }

    private void hideKeyboard() {
        InputMethodManager keyboard = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboard != null) {
            keyboard.hideSoftInputFromWindow(binding.searchInput.getWindowToken(), 0);
        }
        binding.searchInput.clearFocus();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        binding.searchInput.removeTextChangedListener(searchWatcher);
        viewModel.state().removeObserver(observer);
        viewModel.cancelLoad();
        binding.notesList.setAdapter(null);
        if (editor != null) {
            editor.close();
        }
        if (deleteConfirmation != null) {
            deleteConfirmation.dismiss();
        }
    }
}
