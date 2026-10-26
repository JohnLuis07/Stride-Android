package com.stride.android.viewmodel;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.stride.android.model.CalendarDay;
import com.stride.android.model.CalendarEvent;
import com.stride.android.model.CalendarState;
import com.stride.android.model.Goal;
import com.stride.android.model.Task;
import com.stride.android.utils.CalendarDates;
import com.stride.android.utils.FeatureUi;
import com.stride.android.utils.SupabaseApi;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Owns month queries and user-scoped calendar mutations independently of the screen. */
public final class CalendarViewModel extends ViewModel {
    private static final int READ_PAGE_SIZE = 500;

    public interface Result {
        void done(boolean success);
    }

    private final MutableLiveData<CalendarState> state = new MutableLiveData<>();
    private final List<CalendarEvent> events = new ArrayList<>();
    private String selectedDate = CalendarDates.today();
    private String month = CalendarDates.month(selectedDate);
    private SupabaseApi api;
    private String userId;
    private int readGeneration;
    private boolean loading;
    private boolean saving;
    private boolean loadFailed;

    private static final class MonthLoad {
        final int generation;
        final Map<String, CalendarEvent> entries = new LinkedHashMap<>();
        int remaining = 3;
        boolean failed;

        MonthLoad(int generation) {
            this.generation = generation;
        }
    }

    public CalendarViewModel() {
        publish();
    }

    public void configure(SupabaseApi api, String userId) {
        this.api = api;
        this.userId = userId;
    }

    public LiveData<CalendarState> state() {
        return state;
    }

    public void changeMonth(int amount) {
        if (saving) {
            return;
        }
        month = CalendarDates.shiftMonth(month, amount);
        selectedDate = month;
        events.clear();
        reload();
    }

    public void selectDate(String date) {
        if (saving) {
            return;
        }
        String targetMonth = CalendarDates.month(date);
        selectedDate = date;
        if (!targetMonth.equals(month)) {
            month = targetMonth;
            events.clear();
            reload();
        } else {
            publish();
        }
    }

    public void goToToday() {
        selectDate(CalendarDates.today());
    }

    public void reload() {
        if (saving || api == null || userId == null || userId.isEmpty()) {
            return;
        }
        MonthLoad load = new MonthLoad(++readGeneration);
        loading = true;
        loadFailed = false;
        publish();
        String start = CalendarDates.key(CalendarDates.gridStart(month));
        String end = CalendarDates.gridEnd(month);
        readEntries(load, CalendarEvent.Source.EVENT, start, end, 0);
        readEntries(load, CalendarEvent.Source.TASK, start, end, 0);
        readEntries(load, CalendarEvent.Source.GOAL, start, end, 0);
    }

    private void readEntries(MonthLoad load, CalendarEvent.Source source,
            String start, String end, int offset) {
        String table = source == CalendarEvent.Source.TASK ? "tasks"
                : source == CalendarEvent.Source.GOAL ? "goals" : "calendar_events";
        String dateColumn = source == CalendarEvent.Source.TASK ? "due_date"
                : source == CalendarEvent.Source.GOAL ? "target_date" : "event_date";
        api.get(table, SupabaseApi.and("select=*", SupabaseApi.eq("user_id", userId),
                dateColumn + "=gte." + start, dateColumn + "=lte." + end,
                "order=" + dateColumn + ".asc,id.asc", "limit=" + READ_PAGE_SIZE,
                "offset=" + offset), (rows, error) -> {
                    if (load.generation != readGeneration) {
                        return;
                    }
                    if (error != null) {
                        load.failed = true;
                    } else {
                        for (int i = 0; i < rows.length(); i++) {
                            JSONObject record = rows.optJSONObject(i);
                            if (record == null || record.optString("id", "").isEmpty()) {
                                load.failed = true;
                                continue;
                            }
                            CalendarEvent entry = source == CalendarEvent.Source.TASK
                                    ? CalendarEvent.fromTask(Task.fromJson(record))
                                    : source == CalendarEvent.Source.GOAL
                                            ? CalendarEvent.fromGoal(Goal.fromJson(record))
                                            : CalendarEvent.fromJson(record);
                            try {
                                CalendarDates.parse(entry.date);
                                load.entries.put(entry.id, entry);
                            } catch (IllegalArgumentException exception) {
                                load.failed = true;
                            }
                        }
                    }
                    if (!load.failed && rows.length() == READ_PAGE_SIZE) {
                        readEntries(load, source, start, end, offset + rows.length());
                    } else {
                        finishSource(load);
                    }
                });
    }

    private void finishSource(MonthLoad load) {
        if (--load.remaining != 0) {
            return;
        }
        loading = false;
        loadFailed = load.failed;
        // Keep the previous agenda on failure instead of displaying a partial month.
        if (!load.failed) {
            events.clear();
            events.addAll(load.entries.values());
        }
        publish();
    }

    public void refreshDate() {
        publish();
    }

    public void save(CalendarEvent event, Result result) {
        if (saving || event.linked() || event.title.trim().isEmpty()) {
            result.done(false);
            return;
        }
        try {
            CalendarDates.parse(event.date);
            String start = CalendarDates.normalizeTime(event.startTime);
            String end = CalendarDates.normalizeTime(event.endTime);
            if (!start.isEmpty() && !end.isEmpty() && end.compareTo(start) <= 0) {
                result.done(false);
                return;
            }
        } catch (IllegalArgumentException exception) {
            result.done(false);
            return;
        }
        beginMutation();
        JSONObject payload = FeatureUi.obj("user_id", userId, "title", event.title.trim(),
                "details", nullable(event.details.trim()), "event_date", event.date,
                "start_time", nullable(CalendarDates.normalizeTime(event.startTime)),
                "end_time", nullable(CalendarDates.normalizeTime(event.endTime)),
                "category", event.category, "updated_at", FeatureUi.now());
        SupabaseApi.Callback callback = (rows, error) -> {
            saving = false;
            JSONObject record = rows.optJSONObject(0);
            boolean success = error == null && record != null
                    && !record.optString("id", "").isEmpty();
            if (success) {
                String nextMonth = CalendarDates.month(event.date);
                if (!nextMonth.equals(month)) {
                    events.clear();
                }
                month = nextMonth;
                selectedDate = event.date;
                CalendarEvent saved = CalendarEvent.fromJson(record);
                events.removeIf(existing -> existing.id.equals(saved.id));
                events.add(saved);
                loadFailed = false;
            }
            publish();
            result.done(success);
            if (success) {
                reload();
            }
        };
        if (event.id.isEmpty()) {
            api.insert("calendar_events", payload, callback);
        } else {
            api.update("calendar_events", eventQuery(event.id), payload, callback);
        }
    }

    public void delete(CalendarEvent event, Result result) {
        if (saving || event.linked()) {
            result.done(false);
            return;
        }
        beginMutation();
        api.delete("calendar_events", eventQuery(event.id), (rows, error) -> {
            saving = false;
            if (error == null) {
                events.removeIf(existing -> existing.id.equals(event.id));
            }
            publish();
            result.done(error == null);
            if (error == null) {
                reload();
            }
        });
    }

    public void cancelLoad() {
        readGeneration++;
    }

    private String eventQuery(String id) {
        return SupabaseApi.and(SupabaseApi.eq("id", id), SupabaseApi.eq("user_id", userId));
    }

    private void beginMutation() {
        readGeneration++;
        loading = false;
        saving = true;
        publish();
    }

    private Object nullable(String value) {
        return value.isEmpty() ? JSONObject.NULL : value;
    }

    private void publish() {
        Map<String, Integer> counts = new HashMap<>();
        Map<String, CalendarDay.DeadlineStatus> deadlineStatuses = new HashMap<>();
        List<CalendarEvent> agenda = new ArrayList<>();
        for (CalendarEvent event : events) {
            Integer count = counts.get(event.date);
            counts.put(event.date, count == null ? 1 : count + 1);
            if (event.linked()) {
                CalendarDay.DeadlineStatus status = event.overdue()
                        ? CalendarDay.DeadlineStatus.OVERDUE : CalendarDay.DeadlineStatus.OK;
                // One overdue deadline takes priority over other items on the same day.
                if (status == CalendarDay.DeadlineStatus.OVERDUE || !deadlineStatuses.containsKey(event.date)) {
                    deadlineStatuses.put(event.date, status);
                }
            }
            if (event.date.equals(selectedDate)) {
                agenda.add(event);
            }
        }
        Collections.sort(agenda, Comparator.comparing((CalendarEvent event) -> event.startTime)
                .thenComparing(event -> event.title).thenComparing(event -> event.id));
        String today = CalendarDates.today();
        Calendar date = CalendarDates.gridStart(month);
        List<CalendarDay> days = new ArrayList<>();
        for (int i = 0; i < CalendarDates.cellCount(month); i++) {
            String key = CalendarDates.key(date);
            Integer count = counts.get(key);
            CalendarDay.DeadlineStatus deadlineStatus = deadlineStatuses.get(key);
            days.add(new CalendarDay(key, date.get(Calendar.DAY_OF_MONTH),
                    CalendarDates.month(key).equals(month), key.equals(today),
                    key.equals(selectedDate), count == null ? 0 : count,
                    deadlineStatus == null ? CalendarDay.DeadlineStatus.NONE : deadlineStatus));
            date.add(Calendar.DAY_OF_MONTH, 1);
        }
        state.setValue(new CalendarState(month, selectedDate, days, agenda,
                loading, saving, loadFailed));
    }

    @Override
    protected void onCleared() {
        cancelLoad();
    }
}
