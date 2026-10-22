package com.stride.android.viewmodel;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.stride.android.model.Task;
import com.stride.android.model.TasksState;
import com.stride.android.utils.CalendarDates;
import com.stride.android.utils.FeatureUi;
import com.stride.android.utils.SupabaseApi;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** User-scoped task reads and serialized mutations, independent of screen lifetime. */
public final class TasksViewModel extends ViewModel {
    public interface Result {
        void done(boolean success);
    }

    private final MutableLiveData<TasksState> state = new MutableLiveData<>();
    private final List<Task> tasks = new ArrayList<>();
    private SupabaseApi api;
    private String userId;
    private String filter = "all";
    private int readGeneration;
    private boolean loading;
    private boolean saving;
    private boolean loadFailed;

    public TasksViewModel() {
        publish();
    }

    public void configure(SupabaseApi api, String userId) {
        this.api = api;
        this.userId = userId;
    }

    public LiveData<TasksState> state() {
        return state;
    }

    public void setFilter(String filter) {
        if (Arrays.asList("all", "todo", "doing", "done").contains(filter)) {
            this.filter = filter;
            publish();
        }
    }

    public void reload() {
        if (saving) {
            return;
        }
        int request = ++readGeneration;
        loading = true;
        loadFailed = false;
        publish();
        api.get("tasks", SupabaseApi.and("select=*", SupabaseApi.eq("user_id", userId),
                "order=created_at.desc,id.desc"), (rows, error) -> {
                    if (request != readGeneration) {
                        return;
                    }
                    loading = false;
                    loadFailed = error != null;
                    if (error == null) {
                        tasks.clear();
                        for (int i = 0; i < rows.length(); i++) {
                            JSONObject record = rows.optJSONObject(i);
                            if (record != null && !record.optString("id", "").isEmpty()) {
                                tasks.add(Task.fromJson(record));
                            }
                        }
                    }
                    publish();
                });
    }

    public void save(Task task, Result result) {
        if (saving || loading || task.title.trim().isEmpty()) {
            result.done(false);
            return;
        }
        try {
            if (!task.dueDate.isEmpty()) {
                CalendarDates.parse(task.dueDate);
            }
        } catch (IllegalArgumentException exception) {
            result.done(false);
            return;
        }
        beginMutation();
        JSONObject payload = FeatureUi.obj("title", task.title.trim(),
                "details", nullable(task.details.trim()), "due_date", nullable(task.dueDate),
                "priority", task.priority, "updated_at", FeatureUi.now());
        if (task.id.isEmpty()) {
            payload = FeatureUi.obj("user_id", userId, "title", task.title.trim(),
                    "details", nullable(task.details.trim()), "due_date", nullable(task.dueDate),
                    "priority", task.priority, "status", "todo", "updated_at", FeatureUi.now());
            api.insert("tasks", payload, savedCallback("", result));
        } else {
            // Editing descriptive fields preserves the current backend status.
            api.update("tasks", taskQuery(task.id), payload, savedCallback(task.id, result));
        }
    }

    public void changeStatus(Task task, String status, Result result) {
        if (saving || loading || !Arrays.asList("todo", "doing", "done").contains(status)) {
            result.done(false);
            return;
        }
        if (task.status.equals(status)) {
            result.done(true);
            return;
        }
        beginMutation();
        api.update("tasks", taskQuery(task.id),
                FeatureUi.obj("status", status, "updated_at", FeatureUi.now()),
                savedCallback(task.id, result));
    }

    private SupabaseApi.Callback savedCallback(String expectedId, Result result) {
        return (rows, error) -> {
            saving = false;
            JSONObject record = rows.optJSONObject(0);
            String id = record == null ? "" : record.optString("id", "");
            boolean success = error == null && !id.isEmpty()
                    && (expectedId.isEmpty() || expectedId.equals(id));
            if (success) {
                Task saved = Task.fromJson(record);
                int index = -1;
                for (int i = 0; i < tasks.size(); i++) {
                    if (tasks.get(i).id.equals(saved.id)) {
                        index = i;
                        break;
                    }
                }
                if (index < 0) {
                    tasks.add(0, saved);
                } else {
                    tasks.set(index, saved);
                }
            }
            publish();
            result.done(success);
        };
    }

    public void delete(Task task, Result result) {
        if (saving || loading) {
            result.done(false);
            return;
        }
        beginMutation();
        api.delete("tasks", taskQuery(task.id), (rows, error) -> {
            saving = false;
            boolean success = error == null && rows.length() > 0;
            if (success) {
                for (int i = tasks.size() - 1; i >= 0; i--) {
                    if (tasks.get(i).id.equals(task.id)) {
                        tasks.remove(i);
                    }
                }
            }
            publish();
            result.done(success);
        });
    }

    public void cancelLoad() {
        readGeneration++;
        loading = false;
    }

    private String taskQuery(String id) {
        return SupabaseApi.and(SupabaseApi.eq("id", id), SupabaseApi.eq("user_id", userId));
    }

    private void beginMutation() {
        readGeneration++;
        saving = true;
        publish();
    }

    private Object nullable(String value) {
        return value.isEmpty() ? JSONObject.NULL : value;
    }

    private void publish() {
        state.setValue(new TasksState(tasks, filter, loading, saving, loadFailed));
    }

    @Override
    protected void onCleared() {
        cancelLoad();
    }
}
