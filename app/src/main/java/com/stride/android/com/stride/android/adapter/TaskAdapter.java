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
    public interface Actions {
        void onEdit(Task task);
        void onDelete(Task task);
        void onStatus(Task task, String status);
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
                    && oldTask.status.equals(newTask.status);
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
        if (!task.dueDate.isEmpty()) {
            try {
                String date = CalendarDates.label(task.dueDate, "MMM d yyyy");
                overdue = !done && task.dueDate.compareTo(CalendarDates.today()) < 0;
                dateLabel = context.getString(overdue ? R.string.tasks_overdue : R.string.tasks_due, date);
                if (!done && task.dueDate.equals(CalendarDates.today())) {
                    dateLabel = context.getString(R.string.tasks_due_today);
                }
            } catch (IllegalArgumentException exception) {
                // Keep a legacy date visible so it can be corrected in the editor.
                dateLabel = context.getString(R.string.tasks_due, task.dueDate);
            }
        }
        binding.taskDue.setText(dateLabel);
        binding.taskDue.setTextColor(ContextCompat.getColor(context,
                overdue ? R.color.tasks_high : R.color.tasks_muted));
        binding.actionsButton.setEnabled(!busy);
        binding.actionsButton.setContentDescription(context.getString(R.string.tasks_menu_description, task.title));
        binding.actionsButton.setOnClickListener(v -> {
            if (busy) {
                return;
            }
            PopupMenu menu = new PopupMenu(context, binding.actionsButton);
            menu.getMenu().add(0, 1, 0, R.string.tasks_edit);
            menu.getMenu().add(0, 2, 1, R.string.tasks_delete);
            menu.setOnMenuItemClickListener(item -> {
                if (!busy) {
                    if (item.getItemId() == 1) {
                        actions.onEdit(task);
                    } else {
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
        button.setSelected(selected);
        button.setEnabled(!busy);
        button.setTextColor(ContextCompat.getColor(button.getContext(),
                selected ? android.R.color.white : R.color.tasks_accent));
        button.setContentDescription(button.getContext().getString(
                R.string.tasks_status_description, task.title, button.getText()));
        button.setOnClickListener(v -> {
            if (!busy && !selected) {
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
