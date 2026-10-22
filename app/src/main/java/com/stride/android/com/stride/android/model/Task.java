package com.stride.android.model;

import org.json.JSONObject;

/** Immutable task record shared with the web workspace. */
public final class Task {
    public final String id;
    public final String title;
    public final String details;
    public final String dueDate;
    public final String priority;
    public final String status;

    public Task(String id, String title, String details, String dueDate, String priority, String status) {
        this.id = id;
        this.title = title;
        this.details = details;
        this.dueDate = dueDate;
        this.priority = priority;
        this.status = status;
    }

    public static Task fromJson(JSONObject record) {
        String priority = text(record, "priority");
        String status = text(record, "status");
        return new Task(text(record, "id"), text(record, "title"), text(record, "details"),
                text(record, "due_date"), priority.isEmpty() ? "Medium" : priority,
                status.isEmpty() ? "todo" : status);
    }

    private static String text(JSONObject record, String key) {
        return record.isNull(key) ? "" : record.optString(key, "");
    }
}
