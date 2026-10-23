package com.stride.android.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Overview totals are independent of the selected list filter. */
public final class GoalsState {
    public final List<Goal> visibleGoals;
    public final String filter;
    public final int total;
    public final int active;
    public final int completed;
    public final int momentum;
    public final boolean loading;
    public final boolean saving;
    public final boolean loadFailed;

    public GoalsState(List<Goal> goals, String filter, boolean loading, boolean saving, boolean loadFailed) {
        this.filter = filter;
        this.loading = loading;
        this.saving = saving;
        this.loadFailed = loadFailed;
        total = goals.size();
        int finished = 0;
        double progress = 0;
        List<Goal> visible = new ArrayList<>();
        for (Goal goal : goals) {
            boolean done = goal.completed();
            finished += done ? 1 : 0;
            progress += goal.progress();
            if ("all".equals(filter) || ("completed".equals(filter) && done)
                    || ("active".equals(filter) && !done)) {
                visible.add(goal);
            }
        }
        completed = finished;
        active = total - completed;
        momentum = total == 0 ? 0 : (int) Math.round(progress / total);
        visibleGoals = Collections.unmodifiableList(visible);
    }
}
