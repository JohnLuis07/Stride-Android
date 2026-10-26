package com.stride.android.viewmodel;

import android.app.Application;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.stride.android.R;
import com.stride.android.model.Note;
import com.stride.android.model.NoteAiState;
import com.stride.android.utils.FeatureUi;
import com.stride.android.utils.NoteAiApi;
import com.stride.android.utils.SupabaseApi;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Retains the workspace draft and study progress across configuration changes. */
public final class NoteAiViewModel extends AndroidViewModel {
    public static final class StudyProgress {
        public int index;
        public final Set<Integer> revealed = new HashSet<>();
        public final Map<Integer, Integer> answers = new HashMap<>();
    }

    private final MutableLiveData<NoteAiState> state = new MutableLiveData<>();
    private final List<NoteAiState.Source> sources = new ArrayList<>();
    private final List<NoteAiState.Message> messages = new ArrayList<>();
    private final List<NoteAiState.Artifact> artifacts = new ArrayList<>();
    private final Set<String> selected = new HashSet<>();
    private final Map<String, StudyProgress> progress = new HashMap<>();
    private final Map<String, String> savedNoteRequests = new HashMap<>();
    private final Set<String> savedArtifacts = new HashSet<>();
    private NoteAiApi client;
    private SupabaseApi api;
    private Note note;
    private int sessionGeneration, loadGeneration, error, stage;
    private boolean loading, busy, initialized;
    private String tab = "sources", question = "";
    private String pendingSignature = "", pendingRequest = "";

    public NoteAiViewModel(@NonNull Application application) {
        super(application);
        publish();
    }

    public void configure(SupabaseApi api, Note note) {
        if (client == null || this.api != api || sessionGeneration != api.sessionGeneration()) {
            if (client != null) client.close();
            client = new NoteAiApi(getApplication(), api);
            sessionGeneration = api.sessionGeneration();
            loadGeneration++;
            busy = false;
            loading = false;
            pendingRequest = "";
            pendingSignature = "";
        }
        this.api = api;
        this.note = note;
        publish();
    }

    public LiveData<NoteAiState> state() { return state; }
    public StudyProgress studyProgress(String id) {
        StudyProgress value = progress.get(id);
        if (value == null) { value = new StudyProgress(); progress.put(id, value); }
        return value;
    }
    public void tab(String value) { tab = value; publish(); }
    public void question(String value) { question = value; }
    public void select(NoteAiState.Source source, boolean value) {
        if (busy || !source.ready()) return;
        if (value && selected.size() >= 3 && !selected.contains(source.id)) {
            error = R.string.note_ai_selection_limit;
        } else {
            if (value) selected.add(source.id); else selected.remove(source.id);
            error = 0;
        }
        publish();
    }

    public void reload() {
        if (client == null || loading || busy) return;
        int request = ++loadGeneration;
        loading = true;
        error = 0;
        publish();
        client.call(body("overview"), (value, failure) -> {
            if (request != loadGeneration) return;
            loading = false;
            if (failure != null || value == null) {
                error = NoteAiApi.errorResource(failure);
            } else {
                sources.clear(); messages.clear(); artifacts.clear();
                JSONArray rows = value.optJSONArray("sources");
                if (rows != null) for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.optJSONObject(i);
                    if (row != null) sources.add(new NoteAiState.Source(row));
                }
                Set<String> ready = new HashSet<>();
                for (NoteAiState.Source source : sources) if (source.ready()) ready.add(source.id);
                selected.retainAll(ready);
                if (!initialized) {
                    for (NoteAiState.Source source : sources) if (source.ready() && selected.size() < 3) selected.add(source.id);
                    initialized = true;
                }
                rows = value.optJSONArray("messages");
                if (rows != null) for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.optJSONObject(i);
                    if (row != null) messages.add(new NoteAiState.Message(row));
                }
                rows = value.optJSONArray("artifacts");
                if (rows != null) for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.optJSONObject(i);
                    if (row != null) artifacts.add(new NoteAiState.Artifact(row));
                }
                String warning = value.optString("warning");
                if (!warning.isEmpty()) error = NoteAiApi.errorResource(warning);
            }
            publish();
        });
    }

    public void upload(Uri uri) {
        if (!begin(R.string.note_ai_uploading)) return;
        client.upload(note.id, uri, resource -> { stage = resource; publish(); }, (value, failure) -> {
            finish(failure);
            if (failure == null) { tab = "sources"; reload(); }
        });
    }

    public void index(NoteAiState.Source source) { mutation(FeatureUi.obj("action", "index_source", "source_id", source.id)); }
    public void delete(NoteAiState.Source source) { mutation(FeatureUi.obj("action", "delete_source", "source_id", source.id)); }
    private void mutation(JSONObject body) {
        if (!begin(R.string.note_ai_working)) return;
        client.call(body, (value, failure) -> { finish(failure); if (failure == null) reload(); });
    }

    public void generate(String kind) {
        if ("chat".equals(kind) && question.trim().isEmpty()) return;
        if (!begin(R.string.note_ai_thinking)) return;
        List<String> ids = new ArrayList<>(selected);
        java.util.Collections.sort(ids);
        String signature = kind + ":" + question.trim() + ":" + ids;
        if (!signature.equals(pendingSignature) || pendingRequest.isEmpty()) {
            pendingRequest = UUID.randomUUID().toString();
            pendingSignature = signature;
        }
        JSONObject payload = body("chat".equals(kind) ? "chat" : "generate");
        try {
            payload.put("kind", kind).put("question", question.trim()).put("source_ids", new JSONArray(ids))
                    .put("request_id", pendingRequest);
        } catch (org.json.JSONException ignored) { }
        client.call(payload, (value, failure) -> {
            if (failure == null && value != null) {
                if ("chat".equals(kind)) question = "";
                tab = "chat".equals(kind) ? "chat" : "study";
                pendingRequest = "";
                pendingSignature = "";
            } else if (!isTransportFailure(failure)) {
                // A definitive server failure closes the reservation. A network retry reuses its ID.
                pendingRequest = "";
                pendingSignature = "";
            }
            finish(failure);
            if (failure == null) reload();
        });
    }

    private boolean isTransportFailure(String failure) {
        return failure == null || !failure.trim().startsWith("{") || failure.contains("REQUEST_BUSY");
    }

    public void preview(String sourceId, NoteAiApi.FileResult callback) {
        if (!begin(R.string.note_ai_opening_pdf)) return;
        client.preview(sourceId, (file, failure) -> { finish(failure); callback.done(file, failure); });
    }

    public void save(NoteAiState.Artifact artifact, java.util.function.Consumer<Boolean> result) {
        if (!begin(R.string.notes_saving)) { result.accept(false); return; }
        JSONObject payload = body("save_artifact");
        String saveId = savedNoteRequests.get(artifact.id);
        if (saveId == null) { saveId = UUID.randomUUID().toString(); savedNoteRequests.put(artifact.id, saveId); }
        try { payload.put("artifact_id", artifact.id).put("save_id", saveId); } catch (org.json.JSONException ignored) { }
        client.call(payload, (value, failure) -> {
            boolean success = failure == null && value != null && value.optJSONObject("note") != null;
            if (success) savedArtifacts.add(artifact.id);
            finish(failure);
            result.accept(success);
        });
    }

    public boolean wasSaved(String artifactId) { return savedArtifacts.contains(artifactId); }

    private JSONObject body(String action) { return FeatureUi.obj("action", action, "note_id", note.id); }
    private boolean begin(int resource) {
        if (busy || loading || client == null) return false;
        busy = true; error = 0; stage = resource; publish(); return true;
    }
    private void finish(String failure) {
        busy = false; stage = 0; error = failure == null ? 0 : NoteAiApi.errorResource(failure); publish();
    }
    private void publish() {
        state.setValue(new NoteAiState(sources, messages, artifacts, selected, tab, question, loading, busy, error, stage));
    }
    @Override protected void onCleared() {
        loadGeneration++;
        if (client != null) client.close();
    }
}
