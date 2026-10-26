package com.stride.android.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Immutable snapshot of the searchable notebook. */
public final class NotesState {
    public final List<Note> notes;
    public final int total;
    public final String query;
    public final boolean loading;
    public final boolean saving;
    public final boolean loadFailed;

    public NotesState(List<Note> allNotes, String query, boolean loading, boolean saving, boolean loadFailed) {
        this.query = query;
        this.loading = loading;
        this.saving = saving;
        this.loadFailed = loadFailed;
        total = allNotes.size();
        String search = query.trim().toLowerCase(Locale.ROOT);
        List<Note> matching = new ArrayList<>();
        for (Note note : allNotes) {
            if (search.isEmpty() || note.title.toLowerCase(Locale.ROOT).contains(search)
                    || note.preview.toLowerCase(Locale.ROOT).contains(search)) {
                matching.add(note);
            }
        }
        notes = Collections.unmodifiableList(matching);
    }
}
