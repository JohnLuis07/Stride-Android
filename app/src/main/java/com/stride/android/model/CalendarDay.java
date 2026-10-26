package com.stride.android.model;

/** One selectable day, including adjacent-month dates and event markers. */
public final class CalendarDay {
    public enum DeadlineStatus { NONE, OK, OVERDUE }

    public final String date;
    public final int number;
    public final boolean currentMonth;
    public final boolean today;
    public final boolean selected;
    public final int eventCount;
    public final DeadlineStatus deadlineStatus;

    public CalendarDay(String date, int number, boolean currentMonth,
            boolean today, boolean selected, int eventCount, DeadlineStatus deadlineStatus) {
        this.date = date;
        this.number = number;
        this.currentMonth = currentMonth;
        this.today = today;
        this.selected = selected;
        this.eventCount = eventCount;
        this.deadlineStatus = deadlineStatus;
    }
}
