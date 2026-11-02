package com.stride.android.utils;

import com.stride.android.R;
import com.stride.android.model.AllowanceEntry;
import com.stride.android.model.AllowanceSettings;
import org.json.JSONArray;
import org.json.JSONObject;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Account-scoped database operations, history cursors and uncertain-write reconciliation. */
public final class AllowanceRepository {
    public interface Loaded { void done(Snapshot snapshot, int error); }
    public interface Saved { void done(int error); }
    public static final class Snapshot {
        public final AllowanceSettings settings;
        public final List<AllowanceEntry> entries;
        public final BigDecimal income;
        public final BigDecimal expenses;
        public final BigDecimal plannedIncome;
        public final BigDecimal plannedExpenses;
        public final long count;
        Snapshot(AllowanceSettings settings, List<AllowanceEntry> entries, BigDecimal income, BigDecimal expenses,
                BigDecimal plannedIncome, BigDecimal plannedExpenses, long count) {
            this.settings = settings; this.entries = entries; this.income = income; this.expenses = expenses; this.count = count;
            this.plannedIncome = plannedIncome; this.plannedExpenses = plannedExpenses;
        }
    }
    private final SupabaseApi api;
    private final String owner;
    public AllowanceRepository(SupabaseApi api, String owner) { this.api = api; this.owner = owner; }

    public void load(String today, String forecastDate, AllowanceEntry before, Loaded callback) {
        String query = "p_profile=" + SupabaseApi.encode(owner) + "&p_today=" + today + "&p_forecast=" + forecastDate + "&p_limit=50";
        if (before != null) query += "&p_before_date=" + before.date + "&p_before_id=" + SupabaseApi.encode(before.id);
        api.get("rpc/allowance_forecast_snapshot", query, (rows, error) -> {
            if (error != null) { callback.done(null, message(error, R.string.allowance_load_error)); return; }
            try {
                JSONObject row = rows.getJSONObject(0);
                JSONObject settings = row.optJSONObject("settings");
                List<AllowanceEntry> entries = new ArrayList<>();
                JSONArray page = row.getJSONArray("entries");
                for (int i = 0; i < page.length(); i++) entries.add(AllowanceEntry.fromJson(page.getJSONObject(i)));
                callback.done(new Snapshot(settings == null ? AllowanceSettings.defaults() : AllowanceSettings.fromJson(settings),
                        entries, new BigDecimal(row.getString("income_total")), new BigDecimal(row.getString("expense_total")),
                        new BigDecimal(row.getString("planned_income")), new BigDecimal(row.getString("planned_expenses")),
                        row.getLong("entry_count")), 0);
            } catch (Exception failure) { callback.done(null, R.string.allowance_load_error); }
        });
    }

    public void saveEntry(AllowanceEntry entry, boolean existing, Saved callback) {
        JSONObject values = FeatureUi.obj("user_id", owner, "kind", entry.kind, "currency", entry.currency,
                "amount", entry.amount.toPlainString(), "category", entry.category, "note", entry.note, "occurred_on", entry.date,
                "entry_status", entry.status, "source", entry.source);
        SupabaseApi.Callback saved = (rows, error) -> {
            if (error == null && rows.length() == 1) { callback.done(0); return; }
            // Retrying the same editor keeps its UUID; a lost response cannot duplicate income.
            api.get("allowance_entries", ownerQuery(entry.id), (current, readError) -> {
                try {
                    if (readError == null && current.length() == 1 && entry.sameValues(AllowanceEntry.fromJson(current.getJSONObject(0)))) {
                        callback.done(0); return;
                    }
                } catch (Exception ignored) { }
                callback.done(error == null ? R.string.allowance_conflict : message(error, R.string.allowance_save_error));
            });
        };
        if (existing) api.update("allowance_entries", ownerQuery(entry.id) + "&revision=eq." + entry.revision, values, saved);
        else {
            try { values.put("id", entry.id); } catch (Exception ignored) { }
            api.insert("allowance_entries", values, saved);
        }
    }

    public void delete(AllowanceEntry entry, Saved callback) {
        api.delete("allowance_entries", ownerQuery(entry.id) + "&revision=eq." + entry.revision, (rows, error) -> {
            if (error == null && rows.length() == 1) { callback.done(0); return; }
            api.get("allowance_entries", ownerQuery(entry.id), (current, readError) -> {
                if (readError == null && current.length() == 0) callback.done(0);
                else callback.done(error == null ? R.string.allowance_conflict : message(error, R.string.allowance_save_error));
            });
        });
    }

    public void saveSettings(AllowanceSettings settings, Saved callback) {
        JSONObject values = FeatureUi.obj("user_id", owner, "currency", settings.currency,
                "next_payday", settings.nextPayday, "reserved_amount", settings.reserved.toPlainString());
        SupabaseApi.Callback saved = (rows, error) -> {
            if (error == null && rows.length() == 1) { callback.done(0); return; }
            api.get("allowance_settings", SupabaseApi.eq("user_id", owner), (current, readError) -> {
                try {
                    if (readError == null && current.length() == 1 && settings.sameValues(AllowanceSettings.fromJson(current.getJSONObject(0)))) {
                        callback.done(0); return;
                    }
                } catch (Exception ignored) { }
                callback.done(error == null ? R.string.allowance_conflict : message(error, R.string.allowance_save_error));
            });
        };
        if (settings.stored) api.update("allowance_settings", SupabaseApi.eq("user_id", owner) + "&revision=eq." + settings.revision, values, saved);
        else api.insert("allowance_settings", values, saved);
    }

    private String ownerQuery(String id) { return SupabaseApi.and(SupabaseApi.eq("user_id", owner), SupabaseApi.eq("id", id)); }

    private int message(String error, int fallback) {
        if (error.contains("42P01") || error.contains("PGRST202") || error.contains("PGRST205") || error.contains("42883")) return R.string.allowance_setup_error;
        if (error.contains("ALLOWANCE_CURRENCY_LOCKED")) return R.string.allowance_currency_locked;
        if (error.contains("23505") || error.contains("23503")) return R.string.allowance_conflict;
        if (error.contains("STRIDE_SESSION_EXPIRED") || error.contains("42501")) return R.string.allowance_session_error;
        return fallback;
    }
}
