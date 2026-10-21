package com.stride.android.ui;

import android.view.View;

import androidx.activity.ComponentActivity;

import com.stride.android.databinding.ScreenNotesBinding;
import com.stride.android.utils.FeatureUi;
import com.stride.android.utils.SupabaseApi;

import org.json.JSONObject;

public final class NotesScreen extends EntityScreen {

    private final ComponentActivity activity;
    private final ScreenNotesBinding binding;

    public NotesScreen(ComponentActivity activity, SupabaseApi api, JSONObject user) {
        super(activity, api, user, "notes");
        this.activity = activity;
        binding = ScreenNotesBinding.inflate(activity.getLayoutInflater());
    }

    @Override
    public View root() {
        return binding.getRoot();
    }

    public void bind() {
        bindList(activity, binding.listContainer, binding.emptyNotes, "Could not load notes.");
        load();

        binding.addButton.setOnClickListener(v -> showEditor());

        binding.cancelNoteButton.setOnClickListener(
                v -> hideEditor()
        );

        binding.saveNoteButton.setOnClickListener(
                v -> save()
        );
    }

    private void load() {
        viewModel.load("");
    }

    private void showEditor() {
        binding.noteEditor.setVisibility(View.VISIBLE);
        binding.noteTitleInput.requestFocus();
    }

    private void hideEditor() {
        binding.noteEditor.setVisibility(View.GONE);
        binding.noteTitleInput.setText("");
        binding.noteContentInput.setText("");
    }

    private void save() {
        String title = binding.noteTitleInput
                .getText()
                .toString()
                .trim();

        viewModel.insert(
                FeatureUi.obj(
                        "user_id",
                        user.optString("id"),
                        "title",
                        title.isEmpty() ? "Untitled Note" : title,
                        "content",
                        binding.noteContentInput.getText().toString(),
                        "updated_at",
                        FeatureUi.now()
                ),
                (r, e) -> {
                    if (closed) {
                        return;
                    }
                    if (e != null) {
                        ui.toast("Could not save note");
                        return;
                    }

                    hideEditor();
                    load();
                }
        );
    }
}
