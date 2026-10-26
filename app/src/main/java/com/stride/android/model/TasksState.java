package com.stride.android.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Summary counts always reflect all tasks, independently of the active filter. */
public final class TasksState {
    public final List<Task> visibleTasks;
    public final String filter;
    public final int total;
    public final int todo;
    public final int doing;
    public final int done;
    public final boolean loading;
    public final boolean saving;
    public final boolean loadFailed;

    public TasksState(List<Task> tasks, String filter, boolean loading, boolean saving, boolean loadFailed) {
        this.filter = filter;
        this.loading = loading;
        this.saving = saving;
        this.loadFailed = loadFailed;
        total = tasks.size();
        int todoCount = 0;
        int doingCount = 0;
        int doneCount = 0;
        List<Task> visible = new ArrayList<>();
        for (Task task : tasks) {
            if ("todo".equals(task.status)) {
                todoCount++;
            } else if ("doing".equals(task.status)) {
                doingCount++;
            } else if ("done".equals(task.status)) {
                doneCount++;
            }
            if ("all".equals(filter) || filter.equals(task.status)) {
                visible.add(task);
            }
        }
        todo = todoCount;
        doing = doingCount;
        done = doneCount;
        visibleTasks = Collections.unmodifiableList(visible);
    }
}
