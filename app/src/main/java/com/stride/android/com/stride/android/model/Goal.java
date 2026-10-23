package com.stride.android.model;

import org.json.JSONObject;

/** Immutable goal using the same fields as the web workspace. */
public final class Goal {
    public final String id;
    public final String title;
    public final String notes;
    public final double target;
    public final double current;
    public final String unit;
    public final String targetDate;

    public Goal(String id, String title, String notes, double target, double current,
            String unit, String targetDate) {
        this.id = id;
        this.title = title;
        this.notes = notes;
        this.target = target;
        this.current = current;
        this.unit = unit;
        this.targetDate = targetDate;
    }

    public boolean completed() {
        return target > 0 && Double.isFinite(target) && Double.isFinite(current) && current >= target;
    }

    public double progress() {
        return target > 0 && Double.isFinite(target) && Double.isFinite(current)
                ? Math.max(0, Math.min(100, current / target * 100)) : 0;
    }

    public static Goal fromJson(JSONObject record) {
        return new Goal(text(record, "id"), text(record, "title"), text(record, "notes"),
                record.optDouble("target_value", 0), record.optDouble("current_value", 0),
                text(record, "unit"), text(record, "target_date"));
    }

    private static String text(JSONObject record, String key) {
        return record.isNull(key) ? "" : record.optString(key, "");
    }
}
