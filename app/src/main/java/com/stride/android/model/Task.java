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
    public final String deadlineTimezone;
    public final String deadlineAt;
    public final String completedAt;
    public final String completedDeadlineAt;
    public final String deadlineTime;

    public Task(String id, String title, String details, String dueDate, String priority, String status) {
        this(id, title, details, dueDate, priority, status, "");
    }

    public Task(String id, String title, String details, String dueDate, String priority,
            String status, String deadlineTime) {
        this(id, title, details, dueDate, priority, status, "", "", "", "", deadlineTime);
    }

    private Task(String id, String title, String details, String dueDate, String priority, String status,
            String deadlineTimezone, String deadlineAt, String completedAt, String completedDeadlineAt,
            String deadlineTime) {
        this.id = id;
        this.title = title;
        this.details = details;
        this.dueDate = dueDate;
        this.priority = priority;
        this.status = status;
        this.deadlineTimezone = deadlineTimezone;
        this.deadlineAt = deadlineAt;
        this.completedAt = completedAt;
        this.completedDeadlineAt = completedDeadlineAt;
        this.deadlineTime = deadlineTime;
    }

    public static Task fromJson(JSONObject record) {
        String priority = text(record, "priority");
        String status = text(record, "status");
        return new Task(text(record, "id"), text(record, "title"), text(record, "details"),
                text(record, "due_date"), priority.isEmpty() ? "Medium" : priority,
                status.isEmpty() ? "todo" : status, text(record, "deadline_timezone"),
                text(record, "deadline_at"), text(record, "completed_at"), text(record, "completed_deadline_at"),
                text(record, "deadline_time"));
    }

    private static String text(JSONObject record, String key) {
        return record.isNull(key) ? "" : record.optString(key, "");
    }
}
