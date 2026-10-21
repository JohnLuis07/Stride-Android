package com.stride.android.viewmodel;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.stride.android.model.CalendarDay;
import com.stride.android.model.CalendarEvent;
import com.stride.android.model.CalendarState;
import com.stride.android.utils.CalendarDates;
import com.stride.android.utils.FeatureUi;
import com.stride.android.utils.SupabaseApi;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Owns month queries and user-scoped calendar mutations independently of the screen. */
public final class CalendarViewModel extends ViewModel {
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
        int request = ++readGeneration;
        loading = true;
        loadFailed = false;
        publish();
        String start = CalendarDates.key(CalendarDates.gridStart(month));
        String end = CalendarDates.gridEnd(month);
        api.get("calendar_events", SupabaseApi.and("select=*", SupabaseApi.eq("user_id", userId),
                "event_date=gte." + start, "event_date=lte." + end,
                "order=event_date.asc,start_time.asc"), (rows, error) -> {
                    if (request != readGeneration) {
                        return;
                    }
                    loading = false;
                    loadFailed = error != null;
                    if (error == null) {
                        events.clear();
                        for (int i = 0; i < rows.length(); i++) {
                            JSONObject record = rows.optJSONObject(i);
                            if (record != null) {
                                events.add(CalendarEvent.fromJson(record));
                            }
                        }
                    }
                    publish();
                });
    }

    public void save(CalendarEvent event, Result result) {
        if (saving || event.title.trim().isEmpty()) {
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
        if (saving) {
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
        List<CalendarEvent> agenda = new ArrayList<>();
        for (CalendarEvent event : events) {
            Integer count = counts.get(event.date);
            counts.put(event.date, count == null ? 1 : count + 1);
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
            days.add(new CalendarDay(key, date.get(Calendar.DAY_OF_MONTH),
                    CalendarDates.month(key).equals(month), key.equals(today),
                    key.equals(selectedDate), count == null ? 0 : count));
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
