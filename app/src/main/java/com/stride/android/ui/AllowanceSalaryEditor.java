package com.stride.android.ui;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.DialogInterface;
import android.view.View;
import android.view.WindowManager;
import androidx.activity.ComponentActivity;
import com.stride.android.R;
import com.stride.android.databinding.DialogAllowanceSalaryBinding;
import com.stride.android.model.AllowanceEntry;
import com.stride.android.model.AllowanceSettings;
import com.stride.android.utils.AllowanceMoney;
import com.stride.android.utils.CalendarDates;
import com.stride.android.viewmodel.AllowanceViewModel;
import java.math.BigDecimal;
import java.util.Calendar;
import java.util.UUID;

/** Declares one expected salary; receiving it is a separate confirmation. */
final class AllowanceSalaryEditor {
    private final ComponentActivity activity;
    private final AllowanceViewModel model;
    private final AllowanceSettings settings;
    private final DialogAllowanceSalaryBinding binding;
    private final AlertDialog dialog;
    private final String id = UUID.randomUUID().toString();
    private DatePickerDialog picker;
    private String date;
    private boolean closed;
    private boolean saving;

    AllowanceSalaryEditor(ComponentActivity activity, AllowanceViewModel model, AllowanceSettings settings, Runnable dismissed) {
        this.activity = activity;
        this.model = model;
        this.settings = settings;
        date = settings.nextPayday.compareTo(CalendarDates.today()) >= 0 ? settings.nextPayday : CalendarDates.today();
        binding = DialogAllowanceSalaryBinding.inflate(activity.getLayoutInflater());
        binding.amountLabel.setText(activity.getString(R.string.allowance_amount, settings.currency));
        binding.dateButton.setText(CalendarDates.label(date, "EEE MMM d yyyy"));
        binding.dateButton.setOnClickListener(view -> chooseDate());
        dialog = new AlertDialog.Builder(activity).setTitle(R.string.allowance_declare_salary).setView(binding.getRoot())
                .setPositiveButton(R.string.allowance_save, null).setNegativeButton(R.string.allowance_cancel, null).create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(view -> save());
            if (dialog.getWindow() != null) dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        });
        dialog.setOnDismissListener(ignored -> { closed = true; if (picker != null) picker.dismiss(); dismissed.run(); });
    }

    void show() { dialog.show(); }
    void close() { closed = true; if (picker != null) picker.dismiss(); dialog.dismiss(); }

    private void chooseDate() {
        Calendar initial = CalendarDates.parse(date);
        picker = new DatePickerDialog(activity, (view, year, month, day) -> {
            Calendar value = Calendar.getInstance(); value.clear(); value.set(year, month, day, 12, 0, 0);
            date = CalendarDates.key(value);
            binding.dateButton.setText(CalendarDates.label(date, "EEE MMM d yyyy"));
        }, initial.get(Calendar.YEAR), initial.get(Calendar.MONTH), initial.get(Calendar.DAY_OF_MONTH));
        picker.getDatePicker().setMinDate(CalendarDates.parse(CalendarDates.today()).getTimeInMillis());
        picker.getDatePicker().setMaxDate(CalendarDates.parse("2100-12-31").getTimeInMillis());
        picker.show();
    }

    private void save() {
        if (closed || saving) return;
        BigDecimal amount;
        try { amount = AllowanceMoney.parse(binding.amountInput.getText().toString(), false); }
        catch (IllegalArgumentException failure) { binding.amountInput.setError(activity.getString(R.string.allowance_amount_error)); return; }
        AllowanceEntry entry = new AllowanceEntry(id, "income", settings.currency, amount,
                activity.getString(R.string.allowance_salary), binding.noteInput.getText().toString().trim(), date, 0, "planned", "salary");
        busy(true);
        model.saveEntry(entry, false, error -> {
            if (closed) return;
            busy(false);
            if (error == 0) dialog.dismiss();
            else { binding.error.setText(error); binding.error.setVisibility(View.VISIBLE); }
        });
    }

    private void busy(boolean value) {
        saving = value;
        dialog.setCancelable(!value); dialog.setCanceledOnTouchOutside(!value);
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(!value);
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setText(value ? R.string.allowance_saving : R.string.allowance_save);
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setEnabled(!value);
        binding.amountInput.setEnabled(!value); binding.noteInput.setEnabled(!value); binding.dateButton.setEnabled(!value);
        binding.error.setVisibility(View.GONE);
    }
}
