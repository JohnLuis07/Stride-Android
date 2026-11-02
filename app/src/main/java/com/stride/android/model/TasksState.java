package com.stride.android.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Summary counts always reflect all tasks, independently of the active filter. */
public final class TasksState {
    public static final String SORT_NEWEST = "newest";
    public static final String SORT_DEADLINE = "deadline";

    public final List<Task> visibleTasks;
    public final String filter;
    public final String sort;
    public final int total;
    public final int todo;
    public final int doing;
    public final int done;
    public final boolean loading;
    public final boolean saving;
    public final boolean loadFailed;

    public TasksState(List<Task> tasks, String filter, String sort,
            boolean loading, boolean saving, boolean loadFailed) {
        this.filter = filter;
        this.sort = sort;
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
        if (SORT_DEADLINE.equals(sort)) {
            // Sort a copy so equal deadlines retain the original newest-first order.
            Collections.sort(visible, TasksState::compareDeadlines);
        }
        visibleTasks = Collections.unmodifiableList(visible);
    }

    private static int compareDeadlines(Task first, Task second) {
        if (first.dueDate.isEmpty() || second.dueDate.isEmpty()) {
            return Boolean.compare(first.dueDate.isEmpty(), second.dueDate.isEmpty());
        }
        int dateOrder = first.dueDate.compareTo(second.dueDate);
        if (dateOrder != 0) {
            return dateOrder;
        }
        if (first.deadlineTime.isEmpty() || second.deadlineTime.isEmpty()) {
            return Boolean.compare(first.deadlineTime.isEmpty(), second.deadlineTime.isEmpty());
        }
        return first.deadlineTime.compareTo(second.deadlineTime);
    }
}
