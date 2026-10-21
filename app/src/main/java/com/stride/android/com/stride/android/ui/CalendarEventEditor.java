package com.stride.android.ui;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.Dialog;
import android.app.TimePickerDialog;
import android.content.DialogInterface;
import android.text.format.DateFormat;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;

import androidx.activity.ComponentActivity;

import com.stride.android.R;
import com.stride.android.databinding.DialogCalendarEventBinding;
import com.stride.android.model.CalendarEvent;
import com.stride.android.utils.CalendarDates;
import com.stride.android.viewmodel.CalendarViewModel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/** Binding-backed event form with native date/time pickers and inline validation. */
final class CalendarEventEditor {
    private final ComponentActivity activity;
    private final CalendarViewModel viewModel;
    private final DialogCalendarEventBinding binding;
    private final CalendarEvent original;
    private final List<String> categoryValues = new ArrayList<>(
            Arrays.asList("Focus", "Meeting", "Personal", "Deadline"));
    private final AlertDialog dialog;
    private Dialog picker;
    private String date;
    private String startTime;
    private String endTime;
    private boolean closed;

    CalendarEventEditor(ComponentActivity activity, CalendarViewModel viewModel,
            String selectedDate, CalendarEvent original, Runnable onDismiss) {
        this.activity = activity;
        this.viewModel = viewModel;
        this.original = original;
        binding = DialogCalendarEventBinding.inflate(activity.getLayoutInflater());
        date = original == null ? selectedDate : original.date;
        startTime = original == null ? "" : original.startTime;
        endTime = original == null ? "" : original.endTime;
        binding.eventTitleInput.setText(original == null ? "" : original.title);
        binding.eventDetailsInput.setText(original == null ? "" : original.details);
        List<String> categoryLabels = new ArrayList<>(Arrays.asList(
                activity.getResources().getStringArray(R.array.calendar_categories)));
        String category = original == null ? "Focus" : original.category;
        if (!categoryValues.contains(category)) {
            // Preserve categories created by older clients instead of silently replacing them.
            categoryValues.add(category);
            categoryLabels.add(category);
        }
        ArrayAdapter<String> categories = new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_item, categoryLabels);
        categories.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        binding.eventCategorySpinner.setAdapter(categories);
        binding.eventCategorySpinner.setSelection(categoryValues.indexOf(category));
        binding.eventDateButton.setOnClickListener(v -> chooseDate());
        binding.eventStartButton.setOnClickListener(v -> chooseTime(true));
        binding.eventEndButton.setOnClickListener(v -> chooseTime(false));
        binding.clearTimesButton.setOnClickListener(v -> {
            startTime = "";
            endTime = "";
            binding.formError.setVisibility(View.GONE);
            updatePickerLabels();
        });
        updatePickerLabels();
        dialog = new AlertDialog.Builder(activity)
                .setTitle(original == null ? R.string.calendar_new_event : R.string.calendar_edit_event)
                .setView(binding.getRoot())
                .setPositiveButton(original == null ? R.string.calendar_save_event : R.string.calendar_update_event, null)
                .setNegativeButton(R.string.calendar_cancel, null)
                .create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> save());
            if (dialog.getWindow() != null) {
                dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                        | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            }
        });
        dialog.setOnDismissListener(d -> {
            closed = true;
            if (picker != null) {
                picker.dismiss();
            }
            onDismiss.run();
        });
    }

    void show() {
        dialog.show();
    }

    void close() {
        closed = true;
        if (picker != null) {
            picker.dismiss();
        }
        dialog.dismiss();
    }

    private void chooseDate() {
        Calendar selected = CalendarDates.parse(date);
        DatePickerDialog datePicker = new DatePickerDialog(activity, (view, year, month, day) -> {
            Calendar next = Calendar.getInstance();
            next.clear();
            next.set(year, month, day, 12, 0, 0);
            date = CalendarDates.key(next);
            updatePickerLabels();
        }, selected.get(Calendar.YEAR), selected.get(Calendar.MONTH), selected.get(Calendar.DAY_OF_MONTH));
        datePicker.setTitle(R.string.calendar_date_label);
        picker = datePicker;
        datePicker.show();
    }

    private void chooseTime(boolean start) {
        Calendar initial = Calendar.getInstance();
        String current = start ? startTime : endTime;
        try {
            String normalized = CalendarDates.normalizeTime(current);
            if (!normalized.isEmpty()) {
                initial.set(Calendar.HOUR_OF_DAY, Integer.parseInt(normalized.substring(0, 2)));
                initial.set(Calendar.MINUTE, Integer.parseInt(normalized.substring(3, 5)));
            }
        } catch (IllegalArgumentException exception) {
            // A legacy value can be replaced with a valid picker selection.
        }
        TimePickerDialog timePicker = new TimePickerDialog(activity, (view, hour, minute) -> {
            String value = String.format(Locale.US, "%02d:%02d", hour, minute);
            if (start) {
                startTime = value;
            } else {
                endTime = value;
            }
            binding.formError.setVisibility(View.GONE);
            updatePickerLabels();
        }, initial.get(Calendar.HOUR_OF_DAY), initial.get(Calendar.MINUTE), DateFormat.is24HourFormat(activity));
        timePicker.setTitle(start ? R.string.calendar_start_label : R.string.calendar_end_label);
        picker = timePicker;
        timePicker.show();
    }

    private void updatePickerLabels() {
        binding.eventDateButton.setText(CalendarDates.label(date, "EEE MMM d yyyy"));
        binding.eventStartButton.setText(startTime.isEmpty() ? activity.getString(R.string.calendar_optional_time)
                : CalendarDates.timeLabel(activity, startTime));
        binding.eventEndButton.setText(endTime.isEmpty() ? activity.getString(R.string.calendar_optional_time)
                : CalendarDates.timeLabel(activity, endTime));
    }

    private void save() {
        binding.formError.setVisibility(View.GONE);
        String title = binding.eventTitleInput.getText().toString().trim();
        if (title.isEmpty()) {
            binding.eventTitleInput.setError(activity.getString(R.string.calendar_title_required));
            binding.eventTitleInput.requestFocus();
            return;
        }
        String start;
        String end;
        try {
            start = CalendarDates.normalizeTime(startTime);
            end = CalendarDates.normalizeTime(endTime);
        } catch (IllegalArgumentException exception) {
            showError(R.string.calendar_time_invalid);
            return;
        }
        if (!start.isEmpty() && !end.isEmpty() && end.compareTo(start) <= 0) {
            showError(R.string.calendar_end_before_start);
            return;
        }
        CalendarEvent event = new CalendarEvent(original == null ? "" : original.id, title,
                binding.eventDetailsInput.getText().toString().trim(), date, start, end,
                categoryValues.get(binding.eventCategorySpinner.getSelectedItemPosition()));
        setSaving(true);
        viewModel.save(event, success -> {
            if (closed) {
                return;
            }
            if (success) {
                dialog.dismiss();
            } else {
                setSaving(false);
                showError(R.string.calendar_save_failed);
            }
        });
    }

    private void showError(int message) {
        binding.formError.setText(message);
        binding.formError.setVisibility(View.VISIBLE);
    }

    private void setSaving(boolean saving) {
        binding.eventTitleInput.setEnabled(!saving);
        binding.eventDetailsInput.setEnabled(!saving);
        binding.eventDateButton.setEnabled(!saving);
        binding.eventStartButton.setEnabled(!saving);
        binding.eventEndButton.setEnabled(!saving);
        binding.clearTimesButton.setEnabled(!saving);
        binding.eventCategorySpinner.setEnabled(!saving);
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(!saving);
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setText(saving ? R.string.calendar_saving
                : original == null ? R.string.calendar_save_event : R.string.calendar_update_event);
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setEnabled(!saving);
        dialog.setCancelable(!saving);
        dialog.setCanceledOnTouchOutside(!saving);
    }
}
