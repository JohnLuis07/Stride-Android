package com.stride.android.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable snapshot of the visible month and the selected day's agenda. */
public final class CalendarState {
    public final String month;
    public final String selectedDate;
    public final List<CalendarDay> days;
    public final List<CalendarEvent> agenda;
    public final boolean loading;
    public final boolean saving;
    public final boolean loadFailed;

    public CalendarState(String month, String selectedDate, List<CalendarDay> days,
            List<CalendarEvent> agenda, boolean loading, boolean saving, boolean loadFailed) {
        this.month = month;
        this.selectedDate = selectedDate;
        this.days = Collections.unmodifiableList(new ArrayList<>(days));
        this.agenda = Collections.unmodifiableList(new ArrayList<>(agenda));
        this.loading = loading;
        this.saving = saving;
        this.loadFailed = loadFailed;
    }
}
