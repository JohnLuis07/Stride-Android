package com.stride.android.viewmodel;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.stride.android.model.Note;
import com.stride.android.model.NotesState;
import com.stride.android.utils.FeatureUi;
import com.stride.android.utils.SupabaseApi;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Owns note records, the editor draft, and user-scoped backend mutations. */
public final class NotesViewModel extends ViewModel {
    public interface Result {
        void done(boolean success);
    }

    private final List<Note> notes = new ArrayList<>();
    private final MutableLiveData<NotesState> state = new MutableLiveData<>();
    private SupabaseApi api;
    private String userId;
    private String query = "";
    private int generation;
    private boolean loading;
    private boolean saving;
    private boolean loadFailed;
    private boolean editing;
    private String draftId = "";
    private String draftTitle = "";
    private String draftContent = "";
    private String savedTitle = "";
    private String savedContent = "";

    public NotesViewModel() {
        publish();
    }

    public void configure(SupabaseApi api, String userId) {
        this.api = api;
        this.userId = userId;
    }

    public LiveData<NotesState> state() {
        return state;
    }

    public void search(String query) {
        this.query = query;
        publish();
    }

    public void reload() {
        if (saving) {
            return;
        }
        int request = ++generation;
        loading = true;
        loadFailed = false;
        publish();
        api.get("notes", SupabaseApi.and("select=*", SupabaseApi.eq("user_id", userId),
                "order=updated_at.desc,id.desc"), (records, error) -> {
                    if (request != generation) {
                        return;
                    }
                    loading = false;
                    loadFailed = error != null;
                    if (error == null) {
                        notes.clear();
                        for (int i = 0; i < records.length(); i++) {
                            JSONObject record = records.optJSONObject(i);
                            if (record != null && !record.optString("id", "").isEmpty()) {
                                notes.add(Note.fromJson(record));
                            }
                        }
                        sortNotes();
                    }
                    publish();
                });
    }

    public void beginEditing(Note note) {
        if (editing) {
            return;
        }
        editing = true;
        draftId = note == null ? "" : note.id;
        draftTitle = note == null ? "" : note.title;
        draftContent = note == null ? "" : note.content;
        savedTitle = draftTitle;
        savedContent = draftContent;
    }

    public boolean hasDraft() {
        return editing;
    }

    public String draftTitle() {
        return draftTitle;
    }

    public String draftContent() {
        return draftContent;
    }

    public boolean isNewNote() {
        return draftId.isEmpty();
    }

    public boolean isSaving() {
        return saving;
    }

    public void updateTitle(String title) {
        if (editing && !saving) {
            draftTitle = title;
        }
    }

    public void updateContent(String content) {
        if (editing && !saving) {
            draftContent = content;
        }
    }

    public boolean hasChanges() {
        return editing && (!draftTitle.trim().equals(savedTitle.trim()) || !draftContent.equals(savedContent));
    }

    public void finishEditing() {
        if (saving) {
            return;
        }
        editing = false;
        draftId = "";
        draftTitle = "";
        draftContent = "";
        savedTitle = "";
        savedContent = "";
    }

    public void save(String untitled, Result result) {
        if (saving || !editing) {
            result.done(false);
            return;
        }
        beginMutation();
        String title = draftTitle.trim().isEmpty() ? untitled : draftTitle.trim();
        JSONObject payload = FeatureUi.obj("title", title, "content", draftContent,
                "user_id", userId, "updated_at", FeatureUi.now());
        String expectedId = draftId;
        SupabaseApi.Callback callback = (records, error) -> {
            saving = false;
            JSONObject record = records.optJSONObject(0);
            String id = record == null ? "" : record.optString("id", "");
            boolean success = error == null && !id.isEmpty()
                    && (expectedId.isEmpty() || expectedId.equals(id));
            if (success) {
                Note saved = Note.fromJson(record);
                for (int i = notes.size() - 1; i >= 0; i--) {
                    if (notes.get(i).id.equals(saved.id)) {
                        notes.remove(i);
                    }
                }
                notes.add(0, saved);
                sortNotes();
                // Retain the actual backend values as the next editor baseline.
                draftId = saved.id;
                draftTitle = saved.title;
                draftContent = saved.content;
                savedTitle = draftTitle;
                savedContent = draftContent;
            }
            publish();
            result.done(success);
        };
        if (draftId.isEmpty()) {
            api.insert("notes", payload, callback);
        } else {
            api.update("notes", noteQuery(draftId), payload, callback);
        }
    }

    public void delete(Note note, Result result) {
        if (saving || loading || editing) {
            result.done(false);
            return;
        }
        beginMutation();
        api.delete("notes", noteQuery(note.id), (records, error) -> {
            saving = false;
            boolean success = error == null && records.length() > 0;
            if (success) {
                for (int i = notes.size() - 1; i >= 0; i--) {
                    if (notes.get(i).id.equals(note.id)) {
                        notes.remove(i);
                    }
                }
            }
            publish();
            result.done(success);
        });
    }

    public void cancelLoad() {
        generation++;
        loading = false;
    }

    private void beginMutation() {
        generation++;
        loading = false;
        saving = true;
        publish();
    }

    private String noteQuery(String id) {
        return SupabaseApi.and(SupabaseApi.eq("id", id), SupabaseApi.eq("user_id", userId));
    }

    private void sortNotes() {
        Collections.sort(notes, (first, second) -> {
            int byDate = Long.compare(second.updatedAt, first.updatedAt);
            return byDate == 0 ? second.id.compareTo(first.id) : byDate;
        });
    }

    private void publish() {
        state.setValue(new NotesState(notes, query, loading, saving, loadFailed));
    }

    @Override
    protected void onCleared() {
        cancelLoad();
    }
}
