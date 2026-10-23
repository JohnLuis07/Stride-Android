package com.stride.android.adapter;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.PopupMenu;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.AsyncDifferConfig;
import androidx.recyclerview.widget.AsyncListDiffer;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListUpdateCallback;
import androidx.recyclerview.widget.RecyclerView;

import com.stride.android.R;
import com.stride.android.databinding.ItemGoalBinding;
import com.stride.android.databinding.ItemGoalsHeaderBinding;
import com.stride.android.model.Goal;
import com.stride.android.model.GoalsState;
import com.stride.android.utils.CalendarDates;

import java.text.NumberFormat;
import java.util.Collections;

/** One recycled list keeps the overview and goal cards in a single scrolling surface. */
public final class GoalAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    public interface Actions {
        void onCreate();
        void onRefresh();
        void onFilter(String filter);
        void onEdit(Goal goal);
        void onDelete(Goal goal);
        void onProgress(Goal goal);
        void onStep(Goal goal, boolean increase);
    }

    private static final int HEADER = 0;
    private static final int GOAL = 1;
    private static final DiffUtil.ItemCallback<Goal> DIFF = new DiffUtil.ItemCallback<Goal>() {
        @Override
        public boolean areItemsTheSame(@NonNull Goal oldGoal, @NonNull Goal newGoal) {
            return oldGoal.id.equals(newGoal.id);
        }

        @Override
        public boolean areContentsTheSame(@NonNull Goal oldGoal, @NonNull Goal newGoal) {
            return oldGoal.title.equals(newGoal.title) && oldGoal.notes.equals(newGoal.notes)
                    && Double.compare(oldGoal.target, newGoal.target) == 0
                    && Double.compare(oldGoal.current, newGoal.current) == 0
                    && oldGoal.unit.equals(newGoal.unit) && oldGoal.targetDate.equals(newGoal.targetDate);
        }
    };

    private final Actions actions;
    private final AsyncListDiffer<Goal> differ;
    private GoalsState state = new GoalsState(Collections.emptyList(), "all", true, false, false);

    public GoalAdapter(Actions actions) {
        this.actions = actions;
        // Diff callbacks are shifted by one because position zero is the overview.
        differ = new AsyncListDiffer<>(new ListUpdateCallback() {
            @Override
            public void onInserted(int position, int count) {
                notifyItemRangeInserted(position + 1, count);
            }

            @Override
            public void onRemoved(int position, int count) {
                notifyItemRangeRemoved(position + 1, count);
            }

            @Override
            public void onMoved(int fromPosition, int toPosition) {
                notifyItemMoved(fromPosition + 1, toPosition + 1);
            }

            @Override
            public void onChanged(int position, int count, Object payload) {
                notifyItemRangeChanged(position + 1, count, payload);
            }
        }, new AsyncDifferConfig.Builder<>(DIFF).build());
        setStateRestorationPolicy(StateRestorationPolicy.PREVENT);
    }

    public void submitState(GoalsState next) {
        boolean wasBusy = busy();
        state = next;
        notifyItemChanged(0);
        if (wasBusy != busy()) {
            notifyItemRangeChanged(1, differ.getCurrentList().size());
        }
        differ.submitList(next.visibleGoals, () -> setStateRestorationPolicy(
                state.loading ? StateRestorationPolicy.PREVENT : StateRestorationPolicy.ALLOW));
    }

    private boolean busy() {
        return state.loading || state.saving || state.loadFailed;
    }

    @Override
    public int getItemCount() {
        return differ.getCurrentList().size() + 1;
    }

    @Override
    public int getItemViewType(int position) {
        return position == 0 ? HEADER : GOAL;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        return viewType == HEADER
                ? new HeaderHolder(ItemGoalsHeaderBinding.inflate(inflater, parent, false))
                : new GoalHolder(ItemGoalBinding.inflate(inflater, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof HeaderHolder) {
            bindHeader(((HeaderHolder) holder).binding);
        } else {
            bindGoal(((GoalHolder) holder).binding, differ.getCurrentList().get(position - 1));
        }
    }

    private void bindHeader(ItemGoalsHeaderBinding binding) {
        Context context = binding.getRoot().getContext();
        NumberFormat numbers = NumberFormat.getIntegerInstance();
        binding.totalCount.setText(numbers.format(state.total));
        binding.activeCount.setText(numbers.format(state.active));
        binding.completedCount.setText(numbers.format(state.completed));
        binding.momentumPercent.setText(context.getString(R.string.goals_percent, state.momentum));
        binding.momentumProgress.setProgress(state.momentum);
        binding.momentumProgress.setContentDescription(context.getString(R.string.goals_progress_description,
                context.getString(R.string.goals_average), binding.momentumPercent.getText()));
        binding.addButton.setEnabled(!busy());
        binding.addButton.setOnClickListener(v -> {
            if (!busy()) {
                actions.onCreate();
            }
        });
        binding.refreshButton.setEnabled(!state.loading && !state.saving);
        binding.refreshButton.setText(state.loadFailed ? R.string.goals_retry : R.string.goals_refresh);
        binding.refreshButton.setOnClickListener(v -> {
            if (!state.loading && !state.saving) {
                actions.onRefresh();
            }
        });
        bindFilter(binding.filterAll, "all");
        bindFilter(binding.filterActive, "active");
        bindFilter(binding.filterCompleted, "completed");
        binding.resultCount.setText(context.getString(R.string.goals_count, state.visibleGoals.size(), state.total));
        binding.loadingProgress.setVisibility(state.loading || state.saving ? View.VISIBLE : View.GONE);
        boolean feedback = state.loading || state.saving || state.loadFailed;
        binding.feedbackText.setVisibility(feedback ? View.VISIBLE : View.GONE);
        binding.feedbackText.setText(state.saving ? R.string.goals_saving
                : state.loading ? R.string.goals_loading : R.string.goals_load_failed);
        binding.feedbackText.setTextColor(ContextCompat.getColor(context,
                state.loadFailed ? R.color.goals_warning : R.color.goals_muted));
        binding.emptyState.setVisibility(!feedback && state.visibleGoals.isEmpty() ? View.VISIBLE : View.GONE);
        boolean fresh = state.total == 0;
        binding.emptyTitle.setText(fresh ? R.string.goals_empty_title : R.string.goals_filter_empty_title);
        binding.emptyMessage.setText(fresh ? R.string.goals_empty_message : R.string.goals_filter_empty_message);
    }

    private void bindFilter(Button button, String filter) {
        boolean selected = filter.equals(state.filter);
        button.setSelected(selected);
        button.setTextColor(ContextCompat.getColor(button.getContext(),
                selected ? R.color.goals_surface : R.color.goals_accent));
        button.setOnClickListener(v -> actions.onFilter(filter));
    }

    private void bindGoal(ItemGoalBinding binding, Goal goal) {
        Context context = binding.getRoot().getContext();
        NumberFormat numbers = NumberFormat.getNumberInstance();
        numbers.setMaximumFractionDigits(6);
        boolean done = goal.completed();
        boolean validTarget = Double.isFinite(goal.target) && goal.target > 0;
        boolean validCurrent = Double.isFinite(goal.current) && goal.current >= 0;
        binding.getRoot().setBackgroundResource(done ? R.drawable.bg_goals_completed : R.drawable.bg_goals_card);
        binding.goalTitle.setText(goal.title);
        binding.goalAmount.setText(context.getString(R.string.goals_amount,
                numbers.format(goal.current), numbers.format(goal.target), "%").trim());
        binding.goalNotes.setText(goal.notes);
        binding.goalNotes.setVisibility(goal.notes.isEmpty() ? View.GONE : View.VISIBLE);
        binding.completedBadge.setVisibility(done ? View.VISIBLE : View.GONE);
        int color = ContextCompat.getColor(context, done ? R.color.goals_success : R.color.goals_accent);
        // Keep values just below the target from being presented as completed.
        int percent = done ? 100 : Math.min(99, (int) Math.round(goal.progress()));
        binding.goalProgress.setProgress(percent);
        binding.goalProgress.setProgressTintList(ColorStateList.valueOf(color));
        binding.progressLabel.setTextColor(color);
        binding.progressLabel.setText(context.getString(R.string.goals_progress_label, percent));
        binding.goalProgress.setContentDescription(context.getString(R.string.goals_progress_description,
                goal.title, binding.progressLabel.getText()));
        String deadline = context.getString(R.string.goals_no_date);
        boolean overdue = false;
        if (!goal.targetDate.isEmpty()) {
            try {
                String date = CalendarDates.label(goal.targetDate, "MMM d yyyy");
                overdue = !done && goal.targetDate.compareTo(CalendarDates.today()) < 0;
                deadline = context.getString(overdue ? R.string.goals_overdue : R.string.goals_due, date);
                if (!done && goal.targetDate.equals(CalendarDates.today())) {
                    deadline = context.getString(R.string.goals_due_today);
                }
            } catch (IllegalArgumentException exception) {
                deadline = context.getString(R.string.goals_due, goal.targetDate);
            }
        }
        binding.goalDeadline.setText(validTarget ? deadline : context.getString(R.string.goals_invalid_target));
        binding.goalDeadline.setTextColor(ContextCompat.getColor(context,
                overdue || !validTarget ? R.color.goals_warning : R.color.goals_muted));
        binding.actionsButton.setEnabled(!busy());
        binding.actionsButton.setContentDescription(context.getString(R.string.goals_menu, goal.title));
        binding.actionsButton.setOnClickListener(v -> {
            if (busy()) {
                return;
            }
            PopupMenu menu = new PopupMenu(context, binding.actionsButton);
            menu.getMenu().add(0, 1, 0, R.string.goals_edit);
            menu.getMenu().add(0, 2, 1, R.string.goals_delete);
            menu.setOnMenuItemClickListener(item -> {
                if (!busy()) {
                    if (item.getItemId() == 1) {
                        actions.onEdit(goal);
                    } else {
                        actions.onDelete(goal);
                    }
                }
                return true;
            });
            menu.show();
        });
        binding.progressButton.setEnabled(!busy() && validTarget);
        binding.progressButton.setContentDescription(context.getString(R.string.goals_progress_for, goal.title));
        binding.progressButton.setOnClickListener(v -> {
            if (!busy()) {
                actions.onProgress(goal);
            }
        });
        binding.decreaseButton.setEnabled(!busy() && validTarget && validCurrent && goal.current > 0);
        binding.increaseButton.setEnabled(!busy() && validTarget && validCurrent && goal.current < goal.target);
        binding.decreaseButton.setContentDescription(context.getString(R.string.goals_step_description,
                goal.title, context.getString(R.string.goals_minus)));
        binding.increaseButton.setContentDescription(context.getString(R.string.goals_step_description,
                goal.title, context.getString(R.string.goals_plus)));
        binding.decreaseButton.setOnClickListener(v -> {
            if (!busy()) {
                actions.onStep(goal, false);
            }
        });
        binding.increaseButton.setOnClickListener(v -> {
            if (!busy()) {
                actions.onStep(goal, true);
            }
        });
    }

    static final class HeaderHolder extends RecyclerView.ViewHolder {
        final ItemGoalsHeaderBinding binding;

        HeaderHolder(ItemGoalsHeaderBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }

    static final class GoalHolder extends RecyclerView.ViewHolder {
        final ItemGoalBinding binding;

        GoalHolder(ItemGoalBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
