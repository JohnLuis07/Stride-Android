package com.stride.android.ui;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;
import androidx.activity.ComponentActivity;
import androidx.annotation.NonNull;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.stride.android.R;
import com.stride.android.adapter.AllowanceEntryAdapter;
import com.stride.android.databinding.ScreenAllowanceBinding;
import com.stride.android.model.AllowanceEntry;
import com.stride.android.model.AllowanceState;
import com.stride.android.model.AllowanceSummary;
import com.stride.android.utils.AllowanceMoney;
import com.stride.android.utils.CalendarDates;
import com.stride.android.utils.SupabaseApi;
import com.stride.android.viewmodel.AllowanceViewModel;
import org.json.JSONObject;
import java.util.Calendar;

/** Renders a directly bound summary and recycled transaction history. */
public final class AllowanceScreen implements FeatureScreen, DefaultLifecycleObserver {
    private final ComponentActivity activity;
    private final ScreenAllowanceBinding binding;
    private final LoadingView loadingView;
    private final AllowanceViewModel model;
    private final AllowanceEntryAdapter adapter;
    private final Observer<AllowanceState> observer = this::render;
    private CalendarDates.DateObserver dates;
    private AllowanceEntryEditor entryEditor;
    private AllowanceSettingsEditor settingsEditor;
    private AllowanceSalaryEditor salaryEditor;
    private DatePickerDialog forecastPicker;
    private AlertDialog confirmation;
    private boolean closed;
    private String pendingEntryKind;
    private final Runnable loadMore = this::loadNextPage;
    private final Runnable resumeEntry = this::openPendingEntry;
    private final View.OnLayoutChangeListener layoutListener = this::resizeHistory;
    private final RecyclerView.OnScrollListener scroller = new RecyclerView.OnScrollListener() {
        @Override public void onScrolled(@NonNull RecyclerView view, int dx, int dy) {
            LinearLayoutManager layout = (LinearLayoutManager) view.getLayoutManager();
            if (layout != null && view.getAdapter() != null
                    && layout.findLastVisibleItemPosition() >= view.getAdapter().getItemCount() - 5) {
                view.removeCallbacks(loadMore);
                view.post(loadMore);
            }
        }
    };

    public AllowanceScreen(ComponentActivity activity, SupabaseApi api, JSONObject user) {
        this.activity = activity;
        binding = ScreenAllowanceBinding.inflate(activity.getLayoutInflater());
        model = new ViewModelProvider(activity).get("allowance:" + user.optString("id"), AllowanceViewModel.class);
        model.configure(api, user.optString("id"));
        loadingView = new LoadingView(activity, binding.getRoot(), model::reload);
        adapter = new AllowanceEntryAdapter(new AllowanceEntryAdapter.Actions() {
            @Override public void edit(AllowanceEntry entry) { showEntry(entry, entry.kind); }
            @Override public void delete(AllowanceEntry entry) { confirmDelete(entry); }
            @Override public void confirm(AllowanceEntry entry) { confirmEntry(entry); }
        });
    }
    @Override public View root() { return loadingView.root(); }
    private void loadNextPage() { if (!closed) model.loadMore(); }
    private void resizeHistory(View view, int left, int top, int right, int bottom,
            int oldLeft, int oldTop, int oldRight, int oldBottom) {
        updateHistoryHeight();
    }
    private void updateHistoryHeight() {
        AllowanceState state = model.state().getValue();
        int count = state == null ? 0 : state.entries.size();
        binding.entriesList.setVisibility(count == 0 ? View.GONE : View.VISIBLE);
        float density = activity.getResources().getDisplayMetrics().density;
        boolean capped = count > 4;
        int viewport = Math.max(Math.round(240 * density), binding.getRoot().getHeight() - Math.round(48 * density));
        int height = capped ? (int) Math.min((long) viewport, (long) count * Math.round(128 * density))
                : ViewGroup.LayoutParams.WRAP_CONTENT;
        binding.entriesList.setNestedScrollingEnabled(capped);
        ViewGroup.LayoutParams params = binding.entriesList.getLayoutParams();
        if (params.height != height) { params.height = height; binding.entriesList.setLayoutParams(params); }
    }

    public void bind() {
        binding.entriesList.setLayoutManager(new LinearLayoutManager(activity));
        binding.entriesList.setAdapter(adapter);
        binding.getRoot().addOnLayoutChangeListener(layoutListener);
        binding.entriesList.setItemAnimator(null);
        binding.entriesList.addOnScrollListener(scroller);
        binding.addIncome.setOnClickListener(view -> showEntry(null, "income"));
        binding.addExpense.setOnClickListener(view -> showEntry(null, "expense"));
        binding.declareSalary.setOnClickListener(view -> showSalary());
        binding.forecastDate.setOnClickListener(view -> chooseForecastDate());
        binding.settings.setOnClickListener(view -> showSettings());
        binding.refresh.setOnClickListener(view -> model.reload());
        loadingView.update(true, false);
        model.reload();
        model.state().observe(activity, observer);
        dates = new CalendarDates.DateObserver(activity, activity, model::dateChanged);
        activity.getLifecycle().addObserver(this);
    }

    private void render(AllowanceState state) {
        if (closed) return;
        if (state.loading || state.error != 0) {
            loadingView.update(state.loading, state.error != 0);
        }
        adapter.submitList(state.entries, () -> {
            if (!closed && model.state().getValue() == state) {
                updateHistoryHeight();
                loadingView.update(state.loading, state.error != 0);
            }
        });
        updateHistoryHeight();
        boolean idle = state.initialized && !state.loading && !state.saving && state.error == 0;
        adapter.setEnabled(idle);
        binding.addIncome.setEnabled(idle);
        binding.addExpense.setEnabled(idle);
        binding.declareSalary.setEnabled(idle);
        binding.forecastDate.setEnabled(idle);
        binding.addIncome.setAlpha(idle ? 1f : 0.5f);
        binding.addExpense.setAlpha(idle ? 1f : 0.5f);
        binding.settings.setEnabled(idle);
        binding.refresh.setEnabled(!state.loading && !state.saving);
        binding.progress.setVisibility(state.loading || state.saving ? View.VISIBLE : View.GONE);
        binding.error.setVisibility(state.error == 0 ? View.GONE : View.VISIBLE);
        if (state.error != 0) binding.error.setText(state.error);
        binding.empty.setVisibility(state.initialized && state.entries.isEmpty() ? View.VISIBLE : View.GONE);
        binding.setupHint.setVisibility(state.initialized && !state.settings.stored ? View.VISIBLE : View.GONE);
        binding.historyCount.setText(activity.getString(R.string.allowance_history_count, state.entries.size(), state.count));
        binding.historyCount.setVisibility(state.initialized && state.count > 0 ? View.VISIBLE : View.GONE);
        AllowanceSummary summary = state.summary;
        String currency = state.settings.currency;
        binding.forecastDate.setText(activity.getString(R.string.allowance_forecast_date,
                CalendarDates.label(state.forecast.date, "EEE MMM d yyyy")));
        boolean forecastReady = state.initialized && !state.loading && state.error == 0;
        String unavailable = activity.getString(R.string.allowance_unavailable);
        binding.forecastBalance.setText(forecastReady ? AllowanceMoney.format(state.forecast.balance, currency) : unavailable);
        binding.forecastBalance.setTextColor(activity.getColor(state.forecast.balance.signum() < 0 ? R.color.allowance_expense : R.color.allowance_accent));
        binding.forecastIncome.setText(activity.getString(R.string.allowance_planned_income,
                forecastReady ? AllowanceMoney.format(state.forecast.income, currency) : unavailable));
        binding.forecastExpenses.setText(activity.getString(R.string.allowance_planned_expenses,
                forecastReady ? AllowanceMoney.format(state.forecast.expenses, currency) : unavailable));
        binding.forecastSpendable.setText(activity.getString(R.string.allowance_forecast_spendable,
                forecastReady ? AllowanceMoney.format(state.forecast.spendable, currency) : unavailable));
        binding.dailyAmount.setText(!state.initialized || summary.daily == null ? activity.getString(R.string.allowance_unavailable)
                : AllowanceMoney.format(summary.daily, currency));
        if (!state.initialized) binding.paydayDetail.setText(R.string.allowance_stale);
        else if (!state.settings.stored) binding.paydayDetail.setText(R.string.allowance_setup_hint);
        else if (summary.days == 0) binding.paydayDetail.setText(R.string.allowance_payday_due);
        else binding.paydayDetail.setText(activity.getString(R.string.allowance_payday_detail, summary.days,
                    CalendarDates.label(state.settings.nextPayday, "MMM d yyyy")));
        binding.balanceAmount.setText(state.initialized ? AllowanceMoney.format(summary.balance, currency) : activity.getString(R.string.allowance_unavailable));
        binding.reservedAmount.setText(state.initialized ? AllowanceMoney.format(summary.reserved, currency) : activity.getString(R.string.allowance_unavailable));
        binding.spendableAmount.setText(state.initialized ? AllowanceMoney.format(summary.spendable, currency) : activity.getString(R.string.allowance_unavailable));
        binding.spendableAmount.setTextColor(activity.getColor(summary.spendable.signum() < 0 ? R.color.allowance_expense : R.color.allowance_accent));
        binding.incomeTotal.setText(state.initialized ? AllowanceMoney.format(summary.income, currency) : activity.getString(R.string.allowance_unavailable));
        binding.expenseTotal.setText(state.initialized ? AllowanceMoney.format(summary.expenses, currency) : activity.getString(R.string.allowance_unavailable));
        binding.deficit.setVisibility(state.initialized && summary.spendable.signum() < 0 ? View.VISIBLE : View.GONE);
        if (idle && state.settings.stored && pendingEntryKind != null) {
            binding.getRoot().removeCallbacks(resumeEntry);
            binding.getRoot().post(resumeEntry);
        }
    }

    private boolean canOpen() { return !closed && entryEditor == null && settingsEditor == null && salaryEditor == null
            && forecastPicker == null && confirmation == null && model.canAct(); }
    private void showSalary() {
        AllowanceState state = model.state().getValue();
        if (!canOpen() || state == null) return;
        if (!state.settings.stored) { showSettings("salary"); return; }
        salaryEditor = new AllowanceSalaryEditor(activity, model, state.settings, () -> salaryEditor = null);
        salaryEditor.show();
    }
    private void confirmEntry(AllowanceEntry entry) {
        AllowanceState state = model.state().getValue();
        if (!canOpen() || state == null || !entry.planned()) return;
        entryEditor = new AllowanceEntryEditor(activity, model, entry, entry.kind, state.settings.currency, true, () -> entryEditor = null);
        entryEditor.show();
    }
    private void chooseForecastDate() {
        AllowanceState state = model.state().getValue();
        if (!canOpen() || state == null) return;
        Calendar initial = CalendarDates.parse(state.forecast.date);
        forecastPicker = new DatePickerDialog(activity, (view, year, month, day) -> {
            Calendar value = Calendar.getInstance(); value.clear(); value.set(year, month, day, 12, 0, 0);
            model.forecastOn(CalendarDates.key(value));
        }, initial.get(Calendar.YEAR), initial.get(Calendar.MONTH), initial.get(Calendar.DAY_OF_MONTH));
        forecastPicker.getDatePicker().setMinDate(CalendarDates.parse(CalendarDates.today()).getTimeInMillis());
        forecastPicker.getDatePicker().setMaxDate(CalendarDates.parse("2100-12-31").getTimeInMillis());
        forecastPicker.setOnDismissListener(dialog -> forecastPicker = null);
        forecastPicker.show();
    }
    private void showEntry(AllowanceEntry entry, String kind) {
        AllowanceState state = model.state().getValue();
        if (!canOpen() || state == null) return;
        if (!state.settings.stored) {
            Toast.makeText(activity, R.string.allowance_setup_for_entry, Toast.LENGTH_LONG).show();
            showSettings(kind);
            return;
        }
        entryEditor = new AllowanceEntryEditor(activity, model, entry, kind, state.settings.currency, () -> entryEditor = null);
        entryEditor.show();
    }
    private void showSettings() {
        showSettings(null);
    }
    private void showSettings(String afterSetupKind) {
        AllowanceState state = model.state().getValue();
        if (!canOpen() || state == null) return;
        settingsEditor = new AllowanceSettingsEditor(activity, model, state.settings, state.count > 0,
                () -> { if (!closed) pendingEntryKind = afterSetupKind; }, () -> settingsEditor = null);
        settingsEditor.show();
    }
    private void openPendingEntry() {
        if (!activity.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED) || !canOpen()) return;
        AllowanceState state = model.state().getValue();
        if (pendingEntryKind == null || state == null || !state.settings.stored) return;
        String kind = pendingEntryKind;
        pendingEntryKind = null;
        if ("salary".equals(kind)) showSalary();
        else showEntry(null, kind);
    }
    private void confirmDelete(AllowanceEntry entry) {
        if (!canOpen()) return;
        confirmation = new AlertDialog.Builder(activity).setTitle(R.string.allowance_delete_title)
                .setMessage(activity.getString(R.string.allowance_delete_message, entry.category))
                .setNegativeButton(R.string.allowance_cancel, null).setPositiveButton(R.string.allowance_delete,
                        (dialog, which) -> model.delete(entry, error -> {
                            if (!closed && error != 0) Toast.makeText(activity, error, Toast.LENGTH_LONG).show();
                        })).create();
        confirmation.setOnDismissListener(dialog -> confirmation = null);
        confirmation.show();
    }
    @Override public void onResume(@NonNull LifecycleOwner owner) { model.reload(); }
    @Override public void onDestroy(@NonNull LifecycleOwner owner) { close(); }
    @Override public void close() {
        if (closed) return;
        closed = true;
        pendingEntryKind = null;
        binding.getRoot().removeCallbacks(resumeEntry);
        if (dates != null) dates.close();
        activity.getLifecycle().removeObserver(this);
        model.state().removeObserver(observer);
        binding.entriesList.removeCallbacks(loadMore);
        binding.getRoot().removeOnLayoutChangeListener(layoutListener);
        binding.entriesList.removeOnScrollListener(scroller);
        binding.entriesList.setAdapter(null);
        if (entryEditor != null) entryEditor.close();
        if (settingsEditor != null) settingsEditor.close();
        if (salaryEditor != null) salaryEditor.close();
        if (forecastPicker != null) forecastPicker.dismiss();
        if (confirmation != null) confirmation.dismiss();
    }
}
