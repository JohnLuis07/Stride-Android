package com.stride.android.ui;

import android.app.AlertDialog;
import android.view.View;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.ConcatAdapter;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.stride.android.R;
import com.stride.android.adapter.CalendarEventAdapter;
import com.stride.android.adapter.CalendarHeaderAdapter;
import com.stride.android.databinding.ScreenCalendarBinding;
import com.stride.android.model.CalendarEvent;
import com.stride.android.model.CalendarState;
import com.stride.android.utils.CalendarDates;
import com.stride.android.utils.SupabaseApi;
import com.stride.android.viewmodel.CalendarViewModel;

import org.json.JSONObject;

/** Hosts the month grid and daily agenda without changing other feature lists. */
public final class CalendarScreen implements FeatureScreen {
    private final ComponentActivity activity;
    private final ScreenCalendarBinding binding;
    private final CalendarViewModel viewModel;
    private final Observer<CalendarState> observer = this::render;
    private CalendarHeaderAdapter headerAdapter;
    private CalendarEventAdapter eventAdapter;
    private CalendarEventEditor editor;
    private AlertDialog deleteConfirmation;
    private CalendarDates.DateObserver dateObserver;
    private boolean closed;

    public CalendarScreen(ComponentActivity activity, SupabaseApi api, JSONObject user) {
        this.activity = activity;
        binding = ScreenCalendarBinding.inflate(activity.getLayoutInflater());
        viewModel = new ViewModelProvider(activity).get(
                "calendar:" + user.optString("id"), CalendarViewModel.class);
        viewModel.configure(api, user.optString("id"));
    }

    @Override
    public View root() {
        return binding.getRoot();
    }

    public void bind() {
        headerAdapter = new CalendarHeaderAdapter(viewModel.state().getValue(), new CalendarHeaderAdapter.Actions() {
            @Override
            public void onMonthChange(int amount) {
                viewModel.changeMonth(amount);
            }

            @Override
            public void onToday() {
                viewModel.goToToday();
            }

            @Override
            public void onDateSelected(String date) {
                viewModel.selectDate(date);
            }

            @Override
            public void onNewEvent() {
                showEditor(null);
            }

            @Override
            public void onRetry() {
                viewModel.reload();
            }
        });
        eventAdapter = new CalendarEventAdapter(new CalendarEventAdapter.Actions() {
            @Override
            public void onEdit(CalendarEvent event) {
                showEditor(event);
            }

            @Override
            public void onDelete(CalendarEvent event) {
                confirmDelete(event);
            }
        });
        binding.calendarContent.setLayoutManager(new LinearLayoutManager(activity));
        binding.calendarContent.setItemAnimator(null);
        binding.calendarContent.setAdapter(new ConcatAdapter(headerAdapter, eventAdapter));
        viewModel.state().observe(activity, observer);
        dateObserver = new CalendarDates.DateObserver(activity, activity, () -> {
            if (!closed) {
                viewModel.refreshDate();
                eventAdapter.notifyItemRangeChanged(0, eventAdapter.getItemCount());
            }
        });
        viewModel.reload();
    }

    private void render(CalendarState state) {
        if (closed) {
            return;
        }
        headerAdapter.submitState(state);
        eventAdapter.setBusy(state.saving);
        eventAdapter.submitList(state.agenda);
    }

    private void showEditor(CalendarEvent event) {
        CalendarState state = viewModel.state().getValue();
        if (closed || editor != null || state == null || state.saving
                || (event != null && event.linked())) {
            return;
        }
        editor = new CalendarEventEditor(activity, viewModel, state.selectedDate, event,
                () -> editor = null);
        editor.show();
    }

    private void confirmDelete(CalendarEvent event) {
        CalendarState state = viewModel.state().getValue();
        if (closed || state == null || state.saving || event.linked() || deleteConfirmation != null) {
            return;
        }
        deleteConfirmation = new AlertDialog.Builder(activity)
                .setTitle(R.string.calendar_delete_title)
                .setMessage(activity.getString(R.string.calendar_delete_message, event.title))
                .setNegativeButton(R.string.calendar_cancel, null)
                .setPositiveButton(R.string.calendar_delete, (dialog, which) ->
                        viewModel.delete(event, success -> {
                            if (!closed && !success) {
                                Toast.makeText(activity, R.string.calendar_delete_failed, Toast.LENGTH_LONG).show();
                            }
                        }))
                .create();
        deleteConfirmation.setOnDismissListener(dialog -> deleteConfirmation = null);
        deleteConfirmation.show();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (dateObserver != null) {
            dateObserver.close();
        }
        viewModel.state().removeObserver(observer);
        viewModel.cancelLoad();
        binding.calendarContent.setAdapter(null);
        if (editor != null) {
            editor.close();
        }
        if (deleteConfirmation != null) {
            deleteConfirmation.dismiss();
        }
    }
}
