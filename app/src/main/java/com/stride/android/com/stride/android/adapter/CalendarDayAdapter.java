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
import com.stride.android.databinding.ItemCalendarDayBinding;
import com.stride.android.model.CalendarDay;
import com.stride.android.utils.CalendarDates;

import java.text.NumberFormat;

/** A seven-column, binding-backed calendar grid with accessible day descriptions. */
public final class CalendarDayAdapter
        extends ListAdapter<CalendarDay, CalendarDayAdapter.DayHolder> {
    public interface OnDateClickListener {
        void onDateClick(String date);
    }

    private static final DiffUtil.ItemCallback<CalendarDay> DIFF =
            new DiffUtil.ItemCallback<CalendarDay>() {
                @Override
                public boolean areItemsTheSame(@NonNull CalendarDay oldDay, @NonNull CalendarDay newDay) {
                    return oldDay.date.equals(newDay.date);
                }

                @Override
                public boolean areContentsTheSame(@NonNull CalendarDay oldDay, @NonNull CalendarDay newDay) {
                    return oldDay.currentMonth == newDay.currentMonth && oldDay.today == newDay.today
                            && oldDay.selected == newDay.selected && oldDay.eventCount == newDay.eventCount;
                }
            };
    private final OnDateClickListener listener;
    private boolean enabled = true;

    public CalendarDayAdapter(OnDateClickListener listener) {
        super(DIFF);
        this.listener = listener;
    }

    public void setEnabled(boolean enabled) {
        if (this.enabled != enabled) {
            this.enabled = enabled;
            notifyItemRangeChanged(0, getItemCount());
        }
    }

    @NonNull
    @Override
    public DayHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new DayHolder(ItemCalendarDayBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull DayHolder holder, int position) {
        CalendarDay day = getItem(position);
        Context context = holder.itemView.getContext();
        holder.binding.getRoot().setSelected(day.selected);
        holder.binding.getRoot().setActivated(day.today);
        holder.binding.getRoot().setEnabled(enabled);
        holder.binding.dayNumber.setText(NumberFormat.getIntegerInstance().format(day.number));
        int textColor = day.selected ? android.R.color.white : day.today ? R.color.workspace_accent
                : day.currentMonth ? R.color.workspace_ink : R.color.calendar_outside_month;
        holder.binding.dayNumber.setTextColor(ContextCompat.getColor(context, textColor));
        holder.binding.dayEvents.setTextColor(ContextCompat.getColor(context,
                day.selected ? android.R.color.white : R.color.workspace_accent));
        String markers = day.eventCount > 2 ? "+" + NumberFormat.getIntegerInstance().format(day.eventCount)
                : day.eventCount == 2 ? "??" : "?";
        holder.binding.dayEvents.setText(markers);
        holder.binding.dayEvents.setVisibility(day.eventCount > 0 ? View.VISIBLE : View.INVISIBLE);
        String eventCount = context.getResources().getQuantityString(
                R.plurals.calendar_event_count, day.eventCount, day.eventCount);
        holder.binding.getRoot().setContentDescription(context.getString(day.today
                ? R.string.calendar_today_description : R.string.calendar_day_description,
                CalendarDates.label(day.date, "EEEE MMMM d yyyy"), eventCount));
        holder.binding.getRoot().setOnClickListener(v -> listener.onDateClick(day.date));
    }

    static final class DayHolder extends RecyclerView.ViewHolder {
        private final ItemCalendarDayBinding binding;

        DayHolder(ItemCalendarDayBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
