package com.stride.android.ui;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.DialogInterface;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;

import androidx.activity.ComponentActivity;

import com.stride.android.R;
import com.stride.android.databinding.DialogGoalBinding;
import com.stride.android.databinding.DialogGoalProgressBinding;
import com.stride.android.model.Goal;
import com.stride.android.utils.CalendarDates;
import com.stride.android.viewmodel.GoalsViewModel;

import java.math.BigDecimal;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.util.Calendar;

/** Binding-backed goal and progress forms keep drafts open when a save fails. */
final class GoalEditor {
    private final ComponentActivity activity;
    private final GoalsViewModel viewModel;
    private final Goal original;
    private final DialogGoalBinding binding;
    private final DialogGoalProgressBinding progressBinding;
    private final AlertDialog dialog;
    private DatePickerDialog picker;
    private String targetDate;
    private boolean closed;

    GoalEditor(ComponentActivity activity, GoalsViewModel viewModel, Goal original,
            boolean progressOnly, Runnable onDismiss) {
        this.activity = activity;
        this.viewModel = viewModel;
        this.original = original;
        binding = progressOnly ? null : DialogGoalBinding.inflate(activity.getLayoutInflater());
        progressBinding = progressOnly ? DialogGoalProgressBinding.inflate(activity.getLayoutInflater()) : null;
        targetDate = original == null ? "" : original.targetDate;
        if (binding != null) {
            binding.titleInput.setText(original == null ? "" : original.title);
            binding.notesInput.setText(original == null ? "" : original.notes);
            binding.targetInput.setText(numberText(original == null ? 100 : original.target));
            binding.dateButton.setOnClickListener(v -> chooseDate());
            binding.clearDateButton.setOnClickListener(v -> {
                targetDate = "";
                updateDate();
            });
            updateDate();
        } else {
            NumberFormat numbers = NumberFormat.getNumberInstance();
            numbers.setMaximumFractionDigits(6);
            progressBinding.goalAmount.setText(activity.getString(R.string.goals_amount,
                    numbers.format(original.current), numbers.format(original.target), "%").trim());
            progressBinding.currentInput.setText(numberText(original.current));
        }
        dialog = new AlertDialog.Builder(activity)
                .setTitle(progressOnly ? R.string.goals_set_progress
                        : original == null ? R.string.goals_new : R.string.goals_edit)
                .setView(progressOnly ? progressBinding.getRoot() : binding.getRoot())
                .setPositiveButton(original == null ? R.string.goals_create : R.string.goals_update, null)
                .setNegativeButton(R.string.goals_cancel, null)
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

    private String numberText(double value) {
        return Double.isFinite(value) ? BigDecimal.valueOf(value).stripTrailingZeros().toPlainString() : "";
    }

    private void chooseDate() {
        Calendar initial;
        try {
            initial = CalendarDates.parse(targetDate.isEmpty() ? CalendarDates.today() : targetDate);
        } catch (IllegalArgumentException exception) {
            initial = Calendar.getInstance();
        }
        picker = new DatePickerDialog(activity, (view, year, month, day) -> {
            Calendar date = Calendar.getInstance();
            date.clear();
            date.set(year, month, day, 12, 0, 0);
            targetDate = CalendarDates.key(date);
            updateDate();
        }, initial.get(Calendar.YEAR), initial.get(Calendar.MONTH), initial.get(Calendar.DAY_OF_MONTH));
        picker.setTitle(R.string.goals_date_label);
        picker.show();
    }

    private void updateDate() {
        String label = activity.getString(R.string.goals_no_date);
        if (!targetDate.isEmpty()) {
            try {
                label = CalendarDates.label(targetDate, "EEE MMM d yyyy");
            } catch (IllegalArgumentException exception) {
                label = targetDate;
            }
        }
        binding.dateButton.setText(label);
        binding.clearDateButton.setVisibility(targetDate.isEmpty() ? View.GONE : View.VISIBLE);
        binding.formError.setVisibility(View.GONE);
    }

    private double readNumber(EditText field) {
        String raw = field.getText().toString().trim();
        char decimal = DecimalFormatSymbols.getInstance().getDecimalSeparator();
        StringBuilder normalized = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char character = raw.charAt(i);
            int digit = Character.digit(character, 10);
            if (digit >= 0) {
                normalized.append(digit);
            } else if (character == '.' || character == decimal) {
                normalized.append('.');
            } else {
                throw new NumberFormatException();
            }
        }
        double value = Double.parseDouble(normalized.toString());
        if (!Double.isFinite(value)) {
            throw new NumberFormatException();
        }
        return value;
    }

    private void save() {
        if (progressBinding != null) {
            progressBinding.formError.setVisibility(View.GONE);
            double current;
            try {
                current = readNumber(progressBinding.currentInput);
                if (current < 0) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException exception) {
                progressBinding.currentInput.setError(activity.getString(R.string.goals_progress_invalid));
                progressBinding.currentInput.requestFocus();
                return;
            }
            setSaving(true);
            viewModel.updateProgress(original, current, this::onSaved);
            return;
        }
        binding.formError.setVisibility(View.GONE);
        String title = binding.titleInput.getText().toString().trim();
        if (title.isEmpty()) {
            binding.titleInput.setError(activity.getString(R.string.goals_title_required));
            binding.titleInput.requestFocus();
            return;
        }
        double target;
        try {
            target = readNumber(binding.targetInput);
            if (target <= 0) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException exception) {
            binding.targetInput.setError(activity.getString(R.string.goals_target_invalid));
            binding.targetInput.requestFocus();
            return;
        }
        try {
            if (!targetDate.isEmpty()) {
                CalendarDates.parse(targetDate);
            }
        } catch (IllegalArgumentException exception) {
            binding.formError.setText(R.string.goals_date_invalid);
            binding.formError.setVisibility(View.VISIBLE);
            return;
        }
        Goal draft = new Goal(original == null ? "" : original.id, title,
                binding.notesInput.getText().toString().trim(), target,
                original == null ? 0 : original.current,
                "%", targetDate);
        setSaving(true);
        viewModel.save(draft, this::onSaved);
    }

    private void onSaved(boolean success) {
        if (closed) {
            return;
        }
        if (success) {
            dialog.dismiss();
        } else {
            setSaving(false);
            if (binding != null) {
                binding.formError.setText(R.string.goals_save_failed);
                binding.formError.setVisibility(View.VISIBLE);
            } else {
                progressBinding.formError.setText(R.string.goals_save_failed);
                progressBinding.formError.setVisibility(View.VISIBLE);
            }
        }
    }

    private void setSaving(boolean saving) {
        if (binding != null) {
            binding.titleInput.setEnabled(!saving);
            binding.notesInput.setEnabled(!saving);
            binding.targetInput.setEnabled(!saving);
            binding.dateButton.setEnabled(!saving);
            binding.clearDateButton.setEnabled(!saving);
        } else {
            progressBinding.currentInput.setEnabled(!saving);
        }
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(!saving);
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setText(saving ? R.string.goals_saving
                : original == null ? R.string.goals_create : R.string.goals_update);
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setEnabled(!saving);
        dialog.setCancelable(!saving);
        dialog.setCanceledOnTouchOutside(!saving);
    }
}
