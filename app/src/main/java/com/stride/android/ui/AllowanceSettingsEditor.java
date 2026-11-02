package com.stride.android.ui;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.DialogInterface;
import android.view.View;
import androidx.activity.ComponentActivity;
import com.stride.android.R;
import com.stride.android.databinding.DialogAllowanceSettingsBinding;
import com.stride.android.model.AllowanceSettings;
import com.stride.android.utils.AllowanceMoney;
import com.stride.android.utils.CalendarDates;
import com.stride.android.viewmodel.AllowanceViewModel;
import java.math.BigDecimal;
import java.util.Calendar;
import java.util.Locale;

/** Owns only currency, reserved funds and the manually selected next payday. */
final class AllowanceSettingsEditor {
    private final ComponentActivity activity;
    private final DialogAllowanceSettingsBinding binding;
    private final AllowanceSettings original;
    private final AllowanceViewModel model;
    private final boolean currencyLocked;
    private final Runnable saved;
    private final AlertDialog dialog;
    private DatePickerDialog picker;
    private String payday;
    private boolean closed;
    private boolean saving;

    AllowanceSettingsEditor(ComponentActivity activity, AllowanceViewModel model, AllowanceSettings original,
            boolean currencyLocked, Runnable saved, Runnable dismissed) {
        this.activity = activity; this.model = model; this.original = original; this.currencyLocked = currencyLocked;
        this.saved = saved;
        payday = original.nextPayday;
        binding = DialogAllowanceSettingsBinding.inflate(activity.getLayoutInflater());
        binding.currencyInput.setText(original.currency);
        binding.currencyInput.setEnabled(!currencyLocked);
        binding.currencyHint.setText(currencyLocked ? R.string.allowance_currency_locked : R.string.allowance_currency_hint);
        binding.reserveInput.setText(AllowanceMoney.input(original.reserved));
        if (!payday.isEmpty()) binding.paydayButton.setText(CalendarDates.label(payday, "EEE MMM d yyyy"));
        binding.paydayButton.setOnClickListener(view -> chooseDate());
        dialog = new AlertDialog.Builder(activity).setTitle(R.string.allowance_settings).setView(binding.getRoot())
                .setPositiveButton(R.string.allowance_save, null).setNegativeButton(R.string.allowance_cancel, null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(view -> save()));
        dialog.setOnDismissListener(ignored -> { closed = true; if (picker != null) picker.dismiss(); dismissed.run(); });
    }

    void show() { dialog.show(); }
    void close() { closed = true; if (picker != null) picker.dismiss(); dialog.dismiss(); }

    private void chooseDate() {
        Calendar tomorrow = CalendarDates.parse(CalendarDates.today());
        tomorrow.add(Calendar.DAY_OF_MONTH, 1);
        Calendar initial = payday.isEmpty() || payday.compareTo(CalendarDates.today()) <= 0 ? tomorrow : CalendarDates.parse(payday);
        picker = new DatePickerDialog(activity, (view, year, month, day) -> {
            Calendar value = Calendar.getInstance(); value.clear(); value.set(year, month, day, 12, 0, 0);
            payday = CalendarDates.key(value);
            binding.paydayButton.setText(CalendarDates.label(payday, "EEE MMM d yyyy"));
        }, initial.get(Calendar.YEAR), initial.get(Calendar.MONTH), initial.get(Calendar.DAY_OF_MONTH));
        picker.getDatePicker().setMinDate(tomorrow.getTimeInMillis());
        picker.getDatePicker().setMaxDate(CalendarDates.parse("2100-12-31").getTimeInMillis());
        picker.show();
    }

    private void save() {
        if (closed || saving) return;
        String currency = binding.currencyInput.getText().toString().trim().toUpperCase(Locale.ROOT);
        if (!AllowanceMoney.CURRENCIES.contains(currency) || payday.isEmpty() || payday.compareTo(CalendarDates.today()) <= 0) {
            binding.error.setText(R.string.allowance_invalid_settings); binding.error.setVisibility(View.VISIBLE); return;
        }
        BigDecimal reserve;
        try {
            String text = binding.reserveInput.getText().toString().trim();
            reserve = text.isEmpty() ? BigDecimal.ZERO : AllowanceMoney.parse(text, true);
        }
        catch (IllegalArgumentException failure) { binding.reserveInput.setError(activity.getString(R.string.allowance_reserve_error)); return; }
        AllowanceSettings value = new AllowanceSettings(currency, payday, reserve, original.revision, original.stored);
        busy(true);
        model.saveSettings(value, error -> {
            if (closed) return;
            busy(false);
            if (error == 0) { saved.run(); dialog.dismiss(); }
            else { binding.error.setText(error); binding.error.setVisibility(View.VISIBLE); }
        });
    }

    private void busy(boolean value) {
        saving = value;
        dialog.setCancelable(!value); dialog.setCanceledOnTouchOutside(!value);
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(!value);
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setText(value ? R.string.allowance_saving : R.string.allowance_save);
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setEnabled(!value);
        binding.currencyInput.setEnabled(!value && !currencyLocked);
        binding.reserveInput.setEnabled(!value); binding.paydayButton.setEnabled(!value);
        binding.error.setVisibility(View.GONE);
    }
}
