package com.stride.android.ui;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.DialogInterface;
import android.view.View;
import android.view.WindowManager;
import androidx.activity.ComponentActivity;
import com.stride.android.R;
import com.stride.android.databinding.DialogAllowanceEntryBinding;
import com.stride.android.model.AllowanceEntry;
import com.stride.android.utils.AllowanceMoney;
import com.stride.android.utils.CalendarDates;
import com.stride.android.viewmodel.AllowanceViewModel;
import java.math.BigDecimal;
import java.util.Calendar;
import java.util.UUID;

/** Owns only the manual transaction form and its validation. */
final class AllowanceEntryEditor {
    private final ComponentActivity activity;
    private final DialogAllowanceEntryBinding binding;
    private final AllowanceViewModel model;
    private final AllowanceEntry original;
    private final String id;
    private final String kind;
    private final String currency;
    private final AlertDialog dialog;
    private DatePickerDialog picker;
    private String date;
    private boolean closed;
    private boolean saving;
    private final boolean confirming;

    AllowanceEntryEditor(ComponentActivity activity, AllowanceViewModel model, AllowanceEntry original,
            String kind, String currency, Runnable dismissed) {
        this(activity, model, original, kind, currency, false, dismissed);
    }

    AllowanceEntryEditor(ComponentActivity activity, AllowanceViewModel model, AllowanceEntry original,
            String kind, String currency, boolean confirming, Runnable dismissed) {
        this.activity = activity; this.model = model; this.original = original;
        this.confirming = confirming;
        this.kind = original == null ? kind : original.kind;
        this.currency = currency;
        id = original == null ? UUID.randomUUID().toString() : original.id;
        date = original == null || confirming ? CalendarDates.today() : original.date;
        binding = DialogAllowanceEntryBinding.inflate(activity.getLayoutInflater());
        binding.planned.setChecked(original != null && original.planned() && !confirming);
        binding.planned.setVisibility(confirming ? View.GONE : View.VISIBLE);
        binding.statusHint.setText(confirming ? R.string.allowance_confirm_hint : R.string.allowance_plan_hint);
        binding.amountLabel.setText(activity.getString(R.string.allowance_amount, currency));
        if (original != null) {
            binding.amountInput.setText(AllowanceMoney.input(original.amount));
            binding.categoryInput.setText(original.category);
            binding.noteInput.setText(original.note);
        }
        binding.categoryInput.setHint("income".equals(this.kind) ? R.string.allowance_category_income_hint : R.string.allowance_category_expense_hint);
        binding.dateButton.setText(CalendarDates.label(date, "EEE MMM d yyyy"));
        binding.dateButton.setOnClickListener(view -> chooseDate());
        int title = R.string.allowance_edit;
        if (confirming) title = "income".equals(this.kind) ? R.string.allowance_received : R.string.allowance_paid;
        else if (original == null) title = "income".equals(this.kind) ? R.string.allowance_add_income : R.string.allowance_add_expense;
        dialog = new AlertDialog.Builder(activity).setTitle(title)
                .setView(binding.getRoot()).setPositiveButton(R.string.allowance_save, null)
                .setNegativeButton(R.string.allowance_cancel, null).create();
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
            if (!confirming && date.compareTo(CalendarDates.today()) > 0) binding.planned.setChecked(true);
            binding.dateButton.setText(CalendarDates.label(date, "EEE MMM d yyyy"));
        }, initial.get(Calendar.YEAR), initial.get(Calendar.MONTH), initial.get(Calendar.DAY_OF_MONTH));
        picker.getDatePicker().setMinDate(CalendarDates.parse("1900-01-01").getTimeInMillis());
        picker.getDatePicker().setMaxDate(confirming ? System.currentTimeMillis() : CalendarDates.parse("2100-12-31").getTimeInMillis());
        picker.show();
    }

    private void save() {
        if (closed || saving) return;
        BigDecimal amount;
        try { amount = AllowanceMoney.parse(binding.amountInput.getText().toString(), false); }
        catch (IllegalArgumentException failure) { binding.amountInput.setError(activity.getString(R.string.allowance_amount_error)); return; }
        String category = binding.categoryInput.getText().toString().trim();
        if (category.isEmpty()) { binding.categoryInput.setError(activity.getString(R.string.allowance_category_error)); return; }
        AllowanceEntry value = new AllowanceEntry(id, kind, currency, amount, category,
                binding.noteInput.getText().toString().trim(), date, original == null ? 0 : original.revision,
                !confirming && binding.planned.isChecked() ? "planned" : "posted", original == null ? "manual" : original.source);
        busy(true);
        model.saveEntry(value, original != null, error -> {
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
        binding.amountInput.setEnabled(!value); binding.categoryInput.setEnabled(!value);
        binding.planned.setEnabled(!value);
        binding.noteInput.setEnabled(!value); binding.dateButton.setEnabled(!value);
        binding.error.setVisibility(View.GONE);
    }
}
