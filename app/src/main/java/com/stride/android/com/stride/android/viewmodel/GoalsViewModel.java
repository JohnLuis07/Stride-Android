package com.stride.android.viewmodel;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.stride.android.model.Goal;
import com.stride.android.model.GoalsState;
import com.stride.android.utils.CalendarDates;
import com.stride.android.utils.FeatureUi;
import com.stride.android.utils.SupabaseApi;

import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** User-scoped reads and serialized writes outlive individual screen instances. */
public final class GoalsViewModel extends ViewModel {
    public interface Result {
        void done(boolean success);
    }

    private final MutableLiveData<GoalsState> state = new MutableLiveData<>();
    private final List<Goal> goals = new ArrayList<>();
    private SupabaseApi api;
    private String userId;
    private String filter = "all";
    private int readGeneration;
    private boolean loading;
    private boolean saving;
    private boolean loadFailed;

    public GoalsViewModel() {
        publish();
    }

    public void configure(SupabaseApi api, String userId) {
        this.api = api;
        this.userId = userId;
    }

    public LiveData<GoalsState> state() {
        return state;
    }

    public void setFilter(String filter) {
        if (Arrays.asList("all", "active", "completed").contains(filter)) {
            this.filter = filter;
            publish();
        }
    }

    public void reload() {
        if (saving || api == null || userId == null || userId.isEmpty()) {
            return;
        }
        int request = ++readGeneration;
        loading = true;
        loadFailed = false;
        publish();
        api.get("goals", SupabaseApi.and("select=*", SupabaseApi.eq("user_id", userId),
                "order=created_at.desc,id.desc"), (rows, error) -> {
                    if (request != readGeneration) {
                        return;
                    }
                    loading = false;
                    loadFailed = error != null;
                    if (error == null) {
                        goals.clear();
                        for (int i = 0; i < rows.length(); i++) {
                            JSONObject record = rows.optJSONObject(i);
                            if (record != null && !record.optString("id", "").isEmpty()) {
                                goals.add(Goal.fromJson(record));
                            }
                        }
                    }
                    publish();
                });
    }

    public void save(Goal draft, Result result) {
        if (!canWrite() || draft.title.trim().isEmpty() || !Double.isFinite(draft.target)
                || draft.target <= 0) {
            result.done(false);
            return;
        }
        try {
            if (!draft.targetDate.isEmpty()) {
                CalendarDates.parse(draft.targetDate);
            }
        } catch (IllegalArgumentException exception) {
            result.done(false);
            return;
        }
        if (!draft.id.isEmpty() && find(draft.id) == null) {
            result.done(false);
            return;
        }
        JSONObject payload = FeatureUi.obj("title", draft.title.trim(), "notes", nullable(draft.notes.trim()),
                "target_value", draft.target, "unit", "%",
                "target_date", nullable(draft.targetDate), "updated_at", FeatureUi.now());
        beginMutation();
        if (draft.id.isEmpty()) {
            try {
                payload.put("user_id", userId);
                payload.put("current_value", 0);
            } catch (org.json.JSONException exception) {
                saving = false;
                publish();
                result.done(false);
                return;
            }
            api.insert("goals", payload, savedCallback("", result));
        } else {
            // Descriptive edits never overwrite progress written by another client.
            api.update("goals", goalQuery(draft.id), payload, savedCallback(draft.id, result));
        }
    }

    public void stepProgress(Goal goal, boolean increase, Result result) {
        Goal latest = find(goal.id);
        if (latest == null || !Double.isFinite(latest.target) || latest.target <= 0
                || !Double.isFinite(latest.current)) {
            result.done(false);
            return;
        }
        BigDecimal step = BigDecimal.valueOf(latest.target).movePointLeft(1);
        BigDecimal value = BigDecimal.valueOf(latest.current);
        BigDecimal next = increase ? value.add(step).min(BigDecimal.valueOf(latest.target))
                : value.subtract(step).max(BigDecimal.ZERO);
        updateProgress(latest, next.doubleValue(), result);
    }

    public void updateProgress(Goal goal, double next, Result result) {
        if (!canWrite() || find(goal.id) == null || !Double.isFinite(next) || next < 0) {
            result.done(false);
            return;
        }
        beginMutation();
        api.update("goals", goalQuery(goal.id), FeatureUi.obj("current_value", next, "unit", "%",
                "updated_at", FeatureUi.now()), savedCallback(goal.id, result));
    }

    public void delete(Goal goal, Result result) {
        if (!canWrite() || find(goal.id) == null) {
            result.done(false);
            return;
        }
        beginMutation();
        api.delete("goals", goalQuery(goal.id), (rows, error) -> {
            saving = false;
            boolean success = error == null && rows.length() == 1
                    && goal.id.equals(rows.optJSONObject(0) == null ? ""
                    : rows.optJSONObject(0).optString("id", ""));
            if (success) {
                for (int i = goals.size() - 1; i >= 0; i--) {
                    if (goals.get(i).id.equals(goal.id)) {
                        goals.remove(i);
                    }
                }
            }
            publish();
            result.done(success);
        });
    }

    private SupabaseApi.Callback savedCallback(String expectedId, Result result) {
        return (rows, error) -> {
            saving = false;
            JSONObject record = rows.optJSONObject(0);
            String id = record == null ? "" : record.optString("id", "");
            boolean success = error == null && !id.isEmpty()
                    && (expectedId.isEmpty() || expectedId.equals(id));
            if (success) {
                Goal saved = Goal.fromJson(record);
                int index = -1;
                for (int i = 0; i < goals.size(); i++) {
                    if (goals.get(i).id.equals(saved.id)) {
                        index = i;
                        break;
                    }
                }
                if (index < 0) {
                    goals.add(0, saved);
                } else {
                    goals.set(index, saved);
                }
            }
            publish();
            result.done(success);
        };
    }

    private Goal find(String id) {
        for (Goal goal : goals) {
            if (goal.id.equals(id)) {
                return goal;
            }
        }
        return null;
    }

    private boolean canWrite() {
        return !saving && !loading && !loadFailed && api != null && userId != null && !userId.isEmpty();
    }

    private void beginMutation() {
        readGeneration++;
        saving = true;
        publish();
    }

    private String goalQuery(String id) {
        return SupabaseApi.and(SupabaseApi.eq("id", id), SupabaseApi.eq("user_id", userId));
    }

    private Object nullable(String value) {
        return value.isEmpty() ? JSONObject.NULL : value;
    }

    private void publish() {
        state.setValue(new GoalsState(goals, filter, loading, saving, loadFailed));
    }

    public void cancelLoad() {
        readGeneration++;
        loading = false;
        publish();
    }

    @Override
    protected void onCleared() {
        cancelLoad();
    }
}
