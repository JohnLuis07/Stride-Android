package com.stride.android.viewmodel;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import com.stride.android.R;
import com.stride.android.model.AllowanceEntry;
import com.stride.android.model.AllowanceSettings;
import com.stride.android.model.AllowanceState;
import com.stride.android.utils.AllowanceCalculator;
import com.stride.android.utils.AllowanceForecastCalculator;
import com.stride.android.utils.AllowanceMoney;
import com.stride.android.utils.AllowanceRepository;
import com.stride.android.utils.CalendarDates;
import com.stride.android.utils.SupabaseApi;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

/** Coordinates immutable presentation state and serialized user actions. */
public final class AllowanceViewModel extends ViewModel {
    public interface Result { void done(int error); }
    private final MutableLiveData<AllowanceState> state = new MutableLiveData<>();
    private final List<AllowanceEntry> entries = new ArrayList<>();
    private AllowanceRepository repository;
    private AllowanceSettings settings = AllowanceSettings.defaults();
    private BigDecimal income = BigDecimal.ZERO;
    private BigDecimal expenses = BigDecimal.ZERO;
    private BigDecimal plannedIncome = BigDecimal.ZERO;
    private BigDecimal plannedExpenses = BigDecimal.ZERO;
    private String forecastDate = CalendarDates.today();
    private long count;
    private boolean loading;
    private boolean saving;
    private boolean initialized;
    private boolean hasMore;
    private int error;
    private int version;
    private String loadedDay = "";

    public AllowanceViewModel() { publish(); }
    public void configure(SupabaseApi api, String owner) {
        version++;
        loading = false;
        saving = false;
        initialized = false;
        repository = new AllowanceRepository(api, owner);
        publish();
    }
    public LiveData<AllowanceState> state() { return state; }
    public boolean canAct() { return initialized && !loading && !saving && error == 0; }

    public void reload() { load(false); }
    public void forecastOn(String date) {
        if (!canAct() || date.compareTo(CalendarDates.today()) < 0 || date.compareTo("2100-12-31") > 0) return;
        forecastDate = date;
        load(false);
    }
    public void loadMore() { if (hasMore && error == 0) load(true); }
    public void dateChanged() {
        if (!CalendarDates.today().equals(loadedDay)) reload();
        else publish();
    }

    private void load(boolean more) {
        if (repository == null || loading || saving) return;
        String today = CalendarDates.today();
        if (forecastDate.compareTo(today) < 0) forecastDate = today;
        if (more && !today.equals(loadedDay)) more = false;
        boolean append = more;
        AllowanceEntry before = append && !entries.isEmpty() ? entries.get(entries.size() - 1) : null;
        loading = true;
        error = 0;
        publish();
        int request = ++version;
        repository.load(today, forecastDate, before, (snapshot, failure) -> {
            if (request != version) return;
            loading = false;
            error = failure;
            if (failure == 0) {
                settings = snapshot.settings;
                income = snapshot.income;
                expenses = snapshot.expenses;
                plannedIncome = snapshot.plannedIncome;
                plannedExpenses = snapshot.plannedExpenses;
                count = snapshot.count;
                if (!append) entries.clear();
                Set<String> loadedIds = new HashSet<>();
                for (AllowanceEntry entry : entries) loadedIds.add(entry.id);
                for (AllowanceEntry entry : snapshot.entries) {
                    if (loadedIds.add(entry.id)) entries.add(entry);
                }
                hasMore = snapshot.entries.size() == 50;
                initialized = true;
                loadedDay = today;
            }
            publish();
            if (failure == 0 && !today.equals(CalendarDates.today())) reload();
        });
    }

    public void saveEntry(AllowanceEntry entry, boolean existing, Result result) {
        if (!canAct() || !settings.stored) { result.done(R.string.allowance_refresh_first); return; }
        if (!entry.currency.equals(settings.currency)) { result.done(R.string.allowance_conflict); return; }
        if (entry.amount.signum() <= 0 || entry.category.trim().isEmpty() || entry.category.length() > 60
                || entry.note.length() > 280 || (!entry.planned() && entry.date.compareTo(CalendarDates.today()) > 0)
                || entry.date.compareTo("1900-01-01") < 0 || entry.date.compareTo("2100-12-31") > 0
                || !("planned".equals(entry.status) || "posted".equals(entry.status))
                || !("manual".equals(entry.source) || "salary".equals(entry.source)) || (entry.salary() && !entry.income())) {
            result.done(R.string.allowance_invalid_entry); return;
        }
        saving = true;
        publish();
        int request = ++version;
        repository.saveEntry(entry, existing, failure -> finishWrite(request, failure, result));
    }

    public void delete(AllowanceEntry entry, Result result) {
        if (!canAct()) { result.done(R.string.allowance_refresh_first); return; }
        saving = true;
        publish();
        int request = ++version;
        repository.delete(entry, failure -> finishWrite(request, failure, result));
    }

    public void saveSettings(AllowanceSettings value, Result result) {
        if (!canAct()) { result.done(R.string.allowance_refresh_first); return; }
        if (!AllowanceMoney.CURRENCIES.contains(value.currency) || value.reserved.signum() < 0
                || value.nextPayday.compareTo(CalendarDates.today()) <= 0) {
            result.done(R.string.allowance_invalid_settings); return;
        }
        saving = true;
        publish();
        int request = ++version;
        repository.saveSettings(value, failure -> finishWrite(request, failure, result));
    }

    private void finishWrite(int request, int failure, Result result) {
        if (request != version) return;
        saving = false;
        error = failure;
        publish();
        // Acknowledge before refreshing so successful editors close without a second mutation.
        result.done(failure);
        reload();
    }

    private void publish() {
        state.setValue(new AllowanceState(entries, settings, AllowanceCalculator.calculate(income, expenses, settings,
                CalendarDates.today()), AllowanceForecastCalculator.calculate(forecastDate, income.subtract(expenses),
                plannedIncome, plannedExpenses, settings.reserved), loading, saving, initialized, hasMore, count, error));
    }

    @Override protected void onCleared() { version++; }
}
