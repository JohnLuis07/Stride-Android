package com.stride.android.model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Immutable UI snapshot; provider names, tokens, and storage paths never enter it. */
public final class NoteAiState {
    public static final class Source {
        public final String id, title, status, error;
        public final long bytes;
        public final int pages;
        public Source(JSONObject value) {
            id = value.optString("id"); title = value.optString("display_name");
            status = value.optString("status"); error = value.optString("error_code", "");
            bytes = value.optLong("byte_size"); pages = value.optInt("page_count");
        }
        public boolean ready() { return "ready".equals(status); }
    }

    public static final class Citation {
        public final String sourceId, title, excerpt;
        public final int page;
        public Citation(JSONObject value) {
            sourceId = value.optString("source_id"); title = value.optString("title");
            excerpt = value.optString("excerpt"); page = value.optInt("page");
        }
    }

    public static final class Message {
        public final String id, question, answer;
        public final List<Citation> citations;
        public Message(JSONObject value) {
            id = value.optString("id"); question = value.optString("question"); answer = value.optString("answer");
            citations = references(value.optJSONArray("citations"));
        }
    }

    public static final class Artifact {
        public final String id, kind, title, content;
        public final JSONObject data;
        public final List<Citation> citations;
        public Artifact(JSONObject value) {
            id = value.optString("id"); kind = value.optString("kind"); title = value.optString("title");
            content = value.optString("content");
            data = value.optJSONObject("data") == null ? new JSONObject() : value.optJSONObject("data");
            citations = references(value.optJSONArray("citations"));
        }
    }

    public final List<Source> sources;
    public final List<Message> messages;
    public final List<Artifact> artifacts;
    public final Set<String> selectedSources;
    public final String tab, question;
    public final boolean loading, busy;
    public final int errorResource, progressResource;

    public NoteAiState(List<Source> sources, List<Message> messages, List<Artifact> artifacts,
            Set<String> selected, String tab, String question, boolean loading, boolean busy,
            int errorResource, int progressResource) {
        this.sources = Collections.unmodifiableList(new ArrayList<>(sources));
        this.messages = Collections.unmodifiableList(new ArrayList<>(messages));
        this.artifacts = Collections.unmodifiableList(new ArrayList<>(artifacts));
        selectedSources = Collections.unmodifiableSet(new HashSet<>(selected));
        this.tab = tab; this.question = question; this.loading = loading; this.busy = busy;
        this.errorResource = errorResource; this.progressResource = progressResource;
    }

    private static List<Citation> references(JSONArray values) {
        List<Citation> result = new ArrayList<>();
        if (values != null) for (int i = 0; i < values.length(); i++) {
            JSONObject value = values.optJSONObject(i);
            if (value != null && !value.optString("source_id").isEmpty()) result.add(new Citation(value));
        }
        return Collections.unmodifiableList(result);
    }
}
