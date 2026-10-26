package com.stride.android.model;

import com.stride.android.utils.CalendarDates;

import org.json.JSONObject;

/** A manual event or a deadline linked to its original task or goal. */
public final class CalendarEvent {
    public enum Source { EVENT, TASK, GOAL }

    public final String id;
    public final String title;
    public final String details;
    public final String date;
    public final String startTime;
    public final String endTime;
    public final String category;
    public final Source source;
    public final boolean completed;
    public final String deadlineAt;
    public final String completedAt;
    public final String completedDeadlineAt;

    public CalendarEvent(String id, String title, String details, String date,
            String startTime, String endTime, String category) {
        this(id, title, details, date, startTime, endTime, category, Source.EVENT, false, "", "", "");
    }

    private CalendarEvent(String id, String title, String details, String date,
            String startTime, String endTime, String category, Source source, boolean completed,
            String deadlineAt, String completedAt, String completedDeadlineAt) {
        this.id = id;
        this.title = title;
        this.details = details;
        this.date = date;
        this.startTime = startTime;
        this.endTime = endTime;
        this.category = category;
        this.source = source;
        this.completed = completed;
        this.deadlineAt = deadlineAt;
        this.completedAt = completedAt;
        this.completedDeadlineAt = completedDeadlineAt;
    }

    public boolean linked() {
        return source != Source.EVENT;
    }

    public boolean overdue() {
        return linked() && !completed && CalendarDates.isOverdue(date, startTime, deadlineAt);
    }

    public static CalendarEvent fromTask(Task task) {
        return new CalendarEvent("task:" + task.id, task.title, task.details, task.dueDate,
                task.deadlineTime, "", "Deadline", Source.TASK, "done".equals(task.status),
                task.deadlineAt, task.completedAt, task.completedDeadlineAt);
    }

    public static CalendarEvent fromGoal(Goal goal) {
        return new CalendarEvent("goal:" + goal.id, goal.title, goal.notes, goal.targetDate,
                goal.deadlineTime, "", "Deadline", Source.GOAL, goal.completed(),
                goal.deadlineAt, goal.completedAt, goal.completedDeadlineAt);
    }

    public static CalendarEvent fromJson(JSONObject record) {
        String category = value(record, "category");
        return new CalendarEvent(value(record, "id"), value(record, "title"),
                value(record, "details"), value(record, "event_date"),
                value(record, "start_time"), value(record, "end_time"),
                category.isEmpty() ? "Focus" : category);
    }

    private static String value(JSONObject record, String field) {
        return record.isNull(field) ? "" : record.optString(field, "");
    }
}
