package com.stride.android.adapter;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Paint;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.PopupMenu;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.stride.android.R;
import com.stride.android.databinding.ItemTaskBinding;
import com.stride.android.model.Task;
import com.stride.android.utils.CalendarDates;

/** Diffed task cards preserve item identity while status filters change. */
public final class TaskAdapter extends ListAdapter<Task, TaskAdapter.TaskHolder> {
    private static final int MENU_EDIT = 1;
    private static final int MENU_DELETE = 2;
    private static final int MENU_REVERT = 3;

    public interface Actions {
        void onEdit(Task task);
        void onDelete(Task task);
        void onStatus(Task task, String status);
        void onRevert(Task task);
    }

    private static final DiffUtil.ItemCallback<Task> DIFF = new DiffUtil.ItemCallback<Task>() {
        @Override
        public boolean areItemsTheSame(@NonNull Task oldTask, @NonNull Task newTask) {
            return oldTask.id.equals(newTask.id);
        }

        @Override
        public boolean areContentsTheSame(@NonNull Task oldTask, @NonNull Task newTask) {
            return oldTask.title.equals(newTask.title) && oldTask.details.equals(newTask.details)
                    && oldTask.dueDate.equals(newTask.dueDate) && oldTask.priority.equals(newTask.priority)
                    && oldTask.status.equals(newTask.status)
                    && oldTask.deadlineTimezone.equals(newTask.deadlineTimezone)
                    && oldTask.deadlineTime.equals(newTask.deadlineTime)
                    && oldTask.deadlineAt.equals(newTask.deadlineAt)
                    && oldTask.completedAt.equals(newTask.completedAt)
                    && oldTask.completedDeadlineAt.equals(newTask.completedDeadlineAt);
        }
    };
    private final Actions actions;
    private boolean busy;

    public TaskAdapter(Actions actions) {
        super(DIFF);
        this.actions = actions;
    }

    public void setBusy(boolean busy) {
        if (this.busy != busy) {
            this.busy = busy;
            notifyItemRangeChanged(0, getItemCount());
        }
    }

    @NonNull
    @Override
    public TaskHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new TaskHolder(ItemTaskBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull TaskHolder holder, int position) {
        Task task = getItem(position);
        ItemTaskBinding binding = holder.binding;
        Context context = holder.itemView.getContext();
        boolean done = "done".equals(task.status);
        binding.taskTitle.setText(task.title);
        binding.taskTitle.setPaintFlags(done ? binding.taskTitle.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG
                : binding.taskTitle.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
        binding.taskTitle.setTextColor(ContextCompat.getColor(context,
                done ? R.color.tasks_muted : R.color.tasks_ink));
        binding.taskDetails.setText(task.details);
        binding.taskDetails.setVisibility(task.details.isEmpty() ? View.GONE : View.VISIBLE);
        int priorityText = R.color.tasks_low;
        int priorityBackground = R.color.tasks_low_soft;
        String priorityLabel = task.priority;
        if ("High".equals(task.priority)) {
            priorityText = R.color.tasks_high;
            priorityBackground = R.color.tasks_high_soft;
            priorityLabel = context.getString(R.string.tasks_high);
        } else if ("Medium".equals(task.priority)) {
            priorityText = R.color.tasks_medium;
            priorityBackground = R.color.tasks_medium_soft;
            priorityLabel = context.getString(R.string.tasks_medium);
        } else if ("Low".equals(task.priority)) {
            priorityLabel = context.getString(R.string.tasks_low);
        }
        binding.priorityBadge.setText(priorityLabel);
        binding.priorityBadge.setTextColor(ContextCompat.getColor(context, priorityText));
        binding.priorityBadge.setBackgroundTintList(ColorStateList.valueOf(
                ContextCompat.getColor(context, priorityBackground)));
        String dateLabel = context.getString(R.string.tasks_no_date);
        boolean overdue = false;
        boolean validDate = false;
        if (!task.dueDate.isEmpty()) {
            try {
                String date = CalendarDates.deadlineLabel(context, task.dueDate, task.deadlineTime, "MMM d yyyy");
                overdue = !done && CalendarDates.isOverdue(task.dueDate, task.deadlineTime, task.deadlineAt);
                validDate = true;
                dateLabel = context.getString(overdue ? R.string.tasks_overdue : R.string.tasks_due, date);
                if (!done && !overdue && task.dueDate.equals(CalendarDates.today())) {
                    dateLabel = task.deadlineTime.isEmpty() ? context.getString(R.string.tasks_due_today)
                            : context.getString(R.string.calendar_due_today_time, CalendarDates.timeLabel(context, task.deadlineTime));
                }
            } catch (IllegalArgumentException exception) {
                // Keep a legacy date visible so it can be corrected in the editor.
                dateLabel = context.getString(R.string.tasks_due, task.dueDate);
            }
        }
        if (validDate && !overdue) {
            dateLabel = context.getString(R.string.calendar_status_date,
                    context.getString(done ? R.string.calendar_status_completed : R.string.calendar_status_on_track),
                    dateLabel);
        }
        binding.taskDue.setText(dateLabel);
        binding.taskDue.setTextColor(ContextCompat.getColor(context,
                overdue ? R.color.calendar_status_overdue
                        : validDate ? R.color.calendar_status_ok : R.color.tasks_muted));
        CalendarDates.Timing timing = CalendarDates.timing(context, task.dueDate, task.deadlineTime, task.deadlineAt,
                task.completedAt, task.completedDeadlineAt, done);
        binding.taskTiming.setText(timing.text);
        binding.taskTiming.setVisibility(timing.text.isEmpty() ? View.GONE : View.VISIBLE);
        binding.taskTiming.setTextColor(ContextCompat.getColor(context, !timing.available ? R.color.tasks_muted
                : timing.late ? R.color.calendar_status_overdue : R.color.calendar_status_ok));
        binding.actionsButton.setEnabled(!busy);
        binding.actionsButton.setContentDescription(context.getString(R.string.tasks_menu_description, task.title));
        binding.actionsButton.setOnClickListener(v -> {
            if (busy) {
                return;
            }
            PopupMenu menu = new PopupMenu(context, binding.actionsButton);
            menu.getMenu().add(0, MENU_EDIT, 0, R.string.tasks_edit);
            if (done) {
                menu.getMenu().add(0, MENU_REVERT, 1, R.string.tasks_revert_active);
            }
            menu.getMenu().add(0, MENU_DELETE, 2, R.string.tasks_delete);
            menu.setOnMenuItemClickListener(item -> {
                if (!busy) {
                    if (item.getItemId() == MENU_EDIT) {
                        actions.onEdit(task);
                    } else if (item.getItemId() == MENU_REVERT && done) {
                        actions.onRevert(task);
                    } else if (item.getItemId() == MENU_DELETE) {
                        actions.onDelete(task);
                    }
                }
                return true;
            });
            menu.show();
        });
        bindStatus(binding.statusTodo, task, "todo");
        bindStatus(binding.statusDoing, task, "doing");
        bindStatus(binding.statusDone, task, "done");
    }

    private void bindStatus(Button button, Task task, String status) {
        boolean selected = task.status.equals(status);
        boolean done = "done".equals(task.status);
        button.setSelected(selected);
        button.setEnabled(!busy && !done);
        button.setAlpha(done && !selected ? 0.45f : 1f);
        button.setTextColor(ContextCompat.getColor(button.getContext(),
                selected ? android.R.color.white : R.color.tasks_accent));
        button.setContentDescription(done
                ? button.getContext().getString(R.string.tasks_status_locked_description, task.title)
                : button.getContext().getString(R.string.tasks_status_description, task.title, button.getText()));
        button.setOnClickListener(v -> {
            if (!busy && !done && !selected) {
                actions.onStatus(task, status);
            }
        });
    }

    static final class TaskHolder extends RecyclerView.ViewHolder {
        final ItemTaskBinding binding;

        TaskHolder(ItemTaskBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
