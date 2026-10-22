package com.stride.android.ui;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.DialogInterface;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;

import androidx.activity.ComponentActivity;

import com.stride.android.R;
import com.stride.android.databinding.DialogTaskBinding;
import com.stride.android.model.Task;
import com.stride.android.utils.CalendarDates;
import com.stride.android.viewmodel.TasksViewModel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;

/** Binding-backed form retains the draft on errors and uses an optional native date picker. */
final class TaskEditor {
    private final ComponentActivity activity;
    private final TasksViewModel viewModel;
    private final DialogTaskBinding binding;
    private final Task original;
    private final AlertDialog dialog;
    private final List<String> priorities = new ArrayList<>(Arrays.asList("Low", "Medium", "High"));
    private DatePickerDialog picker;
    private String dueDate;
    private boolean closed;

    TaskEditor(ComponentActivity activity, TasksViewModel viewModel, Task original, Runnable onDismiss) {
        this.activity = activity;
        this.viewModel = viewModel;
        this.original = original;
        binding = DialogTaskBinding.inflate(activity.getLayoutInflater());
        binding.titleInput.setText(original == null ? "" : original.title);
        binding.detailsInput.setText(original == null ? "" : original.details);
        dueDate = original == null ? "" : original.dueDate;
        String priority = original == null ? "Medium" : original.priority;
        List<String> labels = new ArrayList<>(Arrays.asList(
                activity.getResources().getStringArray(R.array.tasks_priorities)));
        if (!priorities.contains(priority)) {
            priorities.add(priority);
            labels.add(priority);
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        binding.prioritySpinner.setAdapter(adapter);
        binding.prioritySpinner.setSelection(priorities.indexOf(priority));
        binding.dueButton.setOnClickListener(v -> chooseDate());
        binding.clearDateButton.setOnClickListener(v -> {
            dueDate = "";
            updateDate();
        });
        updateDate();
        dialog = new AlertDialog.Builder(activity)
                .setTitle(original == null ? R.string.tasks_new : R.string.tasks_edit)
                .setView(binding.getRoot())
                .setPositiveButton(original == null ? R.string.tasks_create : R.string.tasks_update, null)
                .setNegativeButton(R.string.tasks_cancel, null)
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
        Calendar initial;
        try {
            initial = CalendarDates.parse(dueDate.isEmpty() ? CalendarDates.today() : dueDate);
        } catch (IllegalArgumentException exception) {
            initial = Calendar.getInstance();
        }
        picker = new DatePickerDialog(activity, (view, year, month, day) -> {
            Calendar date = Calendar.getInstance();
            date.clear();
            date.set(year, month, day, 12, 0, 0);
            dueDate = CalendarDates.key(date);
            updateDate();
        }, initial.get(Calendar.YEAR), initial.get(Calendar.MONTH), initial.get(Calendar.DAY_OF_MONTH));
        picker.setTitle(R.string.tasks_due_label);
        picker.show();
    }

    private void updateDate() {
        String label = activity.getString(R.string.tasks_no_date);
        if (!dueDate.isEmpty()) {
            try {
                label = CalendarDates.label(dueDate, "EEE MMM d yyyy");
            } catch (IllegalArgumentException exception) {
                label = dueDate;
            }
        }
        binding.dueButton.setText(label);
        binding.clearDateButton.setVisibility(dueDate.isEmpty() ? View.GONE : View.VISIBLE);
        binding.formError.setVisibility(View.GONE);
    }

    private void save() {
        binding.formError.setVisibility(View.GONE);
        String title = binding.titleInput.getText().toString().trim();
        if (title.isEmpty()) {
            binding.titleInput.setError(activity.getString(R.string.tasks_title_required));
            binding.titleInput.requestFocus();
            return;
        }
        try {
            if (!dueDate.isEmpty()) {
                CalendarDates.parse(dueDate);
            }
        } catch (IllegalArgumentException exception) {
            showError(R.string.tasks_date_invalid);
            return;
        }
        Task task = new Task(original == null ? "" : original.id, title,
                binding.detailsInput.getText().toString().trim(), dueDate,
                priorities.get(binding.prioritySpinner.getSelectedItemPosition()),
                original == null ? "todo" : original.status);
        setSaving(true);
        viewModel.save(task, success -> {
            if (closed) {
                return;
            }
            if (success) {
                dialog.dismiss();
            } else {
                setSaving(false);
                showError(R.string.tasks_save_failed);
            }
        });
    }

    private void setSaving(boolean saving) {
        binding.titleInput.setEnabled(!saving);
        binding.detailsInput.setEnabled(!saving);
        binding.dueButton.setEnabled(!saving);
        binding.clearDateButton.setEnabled(!saving);
        binding.prioritySpinner.setEnabled(!saving);
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(!saving);
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setText(saving ? R.string.tasks_saving
                : original == null ? R.string.tasks_create : R.string.tasks_update);
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setEnabled(!saving);
        dialog.setCancelable(!saving);
        dialog.setCanceledOnTouchOutside(!saving);
    }

    private void showError(int message) {
        binding.formError.setText(message);
        binding.formError.setVisibility(View.VISIBLE);
    }
}
