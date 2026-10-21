package com.stride.android.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.stride.android.R;
import com.stride.android.databinding.ViewCalendarHeaderBinding;
import com.stride.android.model.CalendarState;
import com.stride.android.utils.CalendarDates;

import java.text.DateFormatSymbols;
import java.util.Calendar;

/** Month controls and grid form the header of the single scrolling agenda. */
public final class CalendarHeaderAdapter
        extends RecyclerView.Adapter<CalendarHeaderAdapter.HeaderHolder> {
    public interface Actions {
        void onMonthChange(int amount);
        void onToday();
        void onDateSelected(String date);
        void onNewEvent();
        void onRetry();
    }

    private CalendarState state;
    private final Actions actions;

    public CalendarHeaderAdapter(CalendarState state, Actions actions) {
        this.state = state;
        this.actions = actions;
    }

    public void submitState(CalendarState state) {
        this.state = state;
        notifyItemChanged(0);
    }

    @NonNull
    @Override
    public HeaderHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new HeaderHolder(ViewCalendarHeaderBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false), actions);
    }

    @Override
    public void onBindViewHolder(@NonNull HeaderHolder holder, int position) {
        Context context = holder.itemView.getContext();
        holder.binding.monthLabel.setText(CalendarDates.label(state.month, "MMMM yyyy"));
        holder.binding.agendaDate.setText(CalendarDates.label(state.selectedDate, "EEE MMM d"));
        holder.binding.eventCount.setText(state.saving ? context.getString(R.string.calendar_saving)
                : state.loading ? context.getString(R.string.calendar_loading)
                : state.loadFailed ? context.getString(R.string.calendar_load_failed)
                : context.getResources().getQuantityString(
                        R.plurals.calendar_event_count, state.agenda.size(), state.agenda.size()));
        holder.binding.loadingIndicator.setVisibility(state.loading || state.saving ? View.VISIBLE : View.GONE);
        holder.binding.errorPanel.setVisibility(state.loadFailed ? View.VISIBLE : View.GONE);
        holder.binding.emptyAgenda.setVisibility(!state.loading && !state.saving
                && !state.loadFailed && state.agenda.isEmpty() ? View.VISIBLE : View.GONE);
        holder.binding.previousMonth.setEnabled(!state.saving);
        holder.binding.nextMonth.setEnabled(!state.saving);
        holder.binding.todayButton.setEnabled(!state.saving);
        holder.binding.newEventButton.setEnabled(!state.saving);
        holder.binding.retryButton.setEnabled(!state.loading && !state.saving);
        holder.dayAdapter.setEnabled(!state.saving);
        holder.dayAdapter.submitList(state.days);
    }

    @Override
    public int getItemCount() {
        return 1;
    }

    static final class HeaderHolder extends RecyclerView.ViewHolder {
        private final ViewCalendarHeaderBinding binding;
        private final CalendarDayAdapter dayAdapter;

        HeaderHolder(ViewCalendarHeaderBinding binding, Actions actions) {
            super(binding.getRoot());
            this.binding = binding;
            dayAdapter = new CalendarDayAdapter(actions::onDateSelected);
            binding.monthGrid.setLayoutManager(new GridLayoutManager(binding.getRoot().getContext(), 7) {
                @Override
                public boolean canScrollVertically() {
                    return false;
                }
            });
            binding.monthGrid.setItemAnimator(null);
            binding.monthGrid.setAdapter(dayAdapter);
            TextView[] labels = {binding.weekdaySunday, binding.weekdayMonday,
                    binding.weekdayTuesday, binding.weekdayWednesday, binding.weekdayThursday,
                    binding.weekdayFriday, binding.weekdaySaturday};
            String[] weekdays = new DateFormatSymbols().getShortWeekdays();
            for (int i = 0; i < labels.length; i++) {
                labels[i].setText(weekdays[Calendar.SUNDAY + i]);
            }
            binding.previousMonth.setOnClickListener(v -> actions.onMonthChange(-1));
            binding.nextMonth.setOnClickListener(v -> actions.onMonthChange(1));
            binding.todayButton.setOnClickListener(v -> actions.onToday());
            binding.newEventButton.setOnClickListener(v -> actions.onNewEvent());
            binding.retryButton.setOnClickListener(v -> actions.onRetry());
        }
    }
}
