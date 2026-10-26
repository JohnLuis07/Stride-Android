package com.stride.android.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.stride.android.R;
import com.stride.android.databinding.ItemCalendarEventBinding;
import com.stride.android.model.CalendarEvent;
import com.stride.android.utils.CalendarDates;

/** Typed event rows preserve the record ID needed for editing and deletion. */
public final class CalendarEventAdapter
        extends ListAdapter<CalendarEvent, CalendarEventAdapter.EventHolder> {
    public interface Actions {
        void onEdit(CalendarEvent event);
        void onDelete(CalendarEvent event);
    }

    private static final DiffUtil.ItemCallback<CalendarEvent> DIFF =
            new DiffUtil.ItemCallback<CalendarEvent>() {
                @Override
                public boolean areItemsTheSame(@NonNull CalendarEvent oldEvent, @NonNull CalendarEvent newEvent) {
                    return oldEvent.id.equals(newEvent.id);
                }

                @Override
                public boolean areContentsTheSame(@NonNull CalendarEvent oldEvent, @NonNull CalendarEvent newEvent) {
                    return oldEvent.title.equals(newEvent.title) && oldEvent.details.equals(newEvent.details)
                            && oldEvent.date.equals(newEvent.date) && oldEvent.startTime.equals(newEvent.startTime)
                            && oldEvent.endTime.equals(newEvent.endTime) && oldEvent.category.equals(newEvent.category)
                            && oldEvent.source == newEvent.source && oldEvent.completed == newEvent.completed
                            && oldEvent.deadlineAt.equals(newEvent.deadlineAt)
                            && oldEvent.completedAt.equals(newEvent.completedAt)
                            && oldEvent.completedDeadlineAt.equals(newEvent.completedDeadlineAt);
                }
            };
    private final Actions actions;
    private boolean busy;

    public CalendarEventAdapter(Actions actions) {
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
    public EventHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new EventHolder(ItemCalendarEventBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull EventHolder holder, int position) {
        CalendarEvent event = getItem(position);
        Context context = holder.itemView.getContext();
        boolean linked = event.linked();
        boolean overdue = event.overdue();
        int statusColor = ContextCompat.getColor(context,
                overdue ? R.color.calendar_status_overdue : R.color.calendar_status_ok);
        holder.binding.eventTitle.setText(event.title);
        holder.binding.eventDetails.setText(event.details);
        holder.binding.eventDetails.setVisibility(event.details.isEmpty() ? View.GONE : View.VISIBLE);
        holder.binding.eventCategory.setText(linked
                ? context.getString(event.source == CalendarEvent.Source.TASK
                        ? R.string.calendar_category_task : R.string.calendar_category_goal)
                : categoryLabel(context, event.category));
        holder.binding.eventCategory.setTextColor(linked ? statusColor
                : ContextCompat.getColor(context, categoryColor(event.category)));
        String start = CalendarDates.timeLabel(context, event.startTime);
        String end = CalendarDates.timeLabel(context, event.endTime);
        String time = start.isEmpty() && end.isEmpty() ? context.getString(R.string.calendar_any_time)
                : start.isEmpty() ? context.getString(R.string.calendar_time_until, end)
                : end.isEmpty() ? context.getString(R.string.calendar_time_from, start)
                : context.getString(R.string.calendar_time_range, start, end);
        String deadlineLabel = linked && !event.startTime.isEmpty()
                ? context.getString(R.string.calendar_deadline_time, CalendarDates.label(event.date, "MMM d yyyy"), start)
                : linked ? context.getString(R.string.calendar_deadline_date, CalendarDates.label(event.date, "MMM d yyyy"))
                : time;
        holder.binding.eventTime.setText(deadlineLabel);
        holder.binding.eventStatus.setVisibility(linked ? View.VISIBLE : View.GONE);
        holder.binding.eventStatus.setText(event.completed ? R.string.calendar_status_completed
                : overdue ? R.string.calendar_status_overdue : R.string.calendar_status_on_track);
        holder.binding.eventStatus.setTextColor(statusColor);
        CalendarDates.Timing timing = linked ? CalendarDates.timing(context, event.date, event.startTime, event.deadlineAt,
                event.completedAt, event.completedDeadlineAt, event.completed) : null;
        holder.binding.eventTiming.setText(timing == null ? "" : timing.text);
        holder.binding.eventTiming.setVisibility(timing == null || timing.text.isEmpty() ? View.GONE : View.VISIBLE);
        holder.binding.eventTiming.setTextColor(ContextCompat.getColor(context,
                timing == null || !timing.available ? R.color.workspace_secondary
                        : timing.late ? R.color.calendar_status_overdue : R.color.calendar_status_ok));
        holder.binding.eventSourceHint.setVisibility(linked ? View.VISIBLE : View.GONE);
        holder.binding.eventSourceHint.setText(event.source == CalendarEvent.Source.TASK
                ? R.string.calendar_task_source_hint : R.string.calendar_goal_source_hint);
        holder.binding.eventActions.setVisibility(linked ? View.GONE : View.VISIBLE);
        holder.binding.editButton.setEnabled(!busy && !linked);
        holder.binding.deleteButton.setEnabled(!busy && !linked);
        holder.binding.editButton.setContentDescription(context.getString(R.string.calendar_edit_description, event.title));
        holder.binding.deleteButton.setContentDescription(context.getString(R.string.calendar_delete_description, event.title));
        holder.binding.editButton.setOnClickListener(v -> {
            if (!busy && !linked) {
                actions.onEdit(event);
            }
        });
        holder.binding.deleteButton.setOnClickListener(v -> {
            if (!busy && !linked) {
                actions.onDelete(event);
            }
        });
    }

    private String categoryLabel(Context context, String category) {
        switch (category) {
            case "Focus": return context.getString(R.string.calendar_category_focus);
            case "Meeting": return context.getString(R.string.calendar_category_meeting);
            case "Personal": return context.getString(R.string.calendar_category_personal);
            case "Deadline": return context.getString(R.string.calendar_category_deadline);
            default: return category;
        }
    }

    private int categoryColor(String category) {
        switch (category) {
            case "Meeting": return R.color.calendar_meeting;
            case "Personal": return R.color.calendar_personal;
            case "Deadline": return R.color.calendar_deadline;
            default: return R.color.calendar_focus;
        }
    }

    static final class EventHolder extends RecyclerView.ViewHolder {
        private final ItemCalendarEventBinding binding;

        EventHolder(ItemCalendarEventBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
