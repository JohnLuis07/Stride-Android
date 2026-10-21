package com.stride.android.model;

import org.json.JSONObject;

/** A calendar_events record without a dependency on its rendered row. */
public final class CalendarEvent {
    public final String id;
    public final String title;
    public final String details;
    public final String date;
    public final String startTime;
    public final String endTime;
    public final String category;

    public CalendarEvent(String id, String title, String details, String date,
            String startTime, String endTime, String category) {
        this.id = id;
        this.title = title;
        this.details = details;
        this.date = date;
        this.startTime = startTime;
        this.endTime = endTime;
        this.category = category;
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
