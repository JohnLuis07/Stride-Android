package com.stride.android.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One read-only presentation snapshot; no database or calculation behavior. */
public final class AllowanceState {
    public final List<AllowanceEntry> entries;
    public final AllowanceSettings settings;
    public final AllowanceSummary summary;
    public final AllowanceForecast forecast;
    public final boolean loading;
    public final boolean saving;
    public final boolean initialized;
    public final boolean hasMore;
    public final long count;
    public final int error;

    public AllowanceState(List<AllowanceEntry> entries, AllowanceSettings settings, AllowanceSummary summary, AllowanceForecast forecast,
            boolean loading, boolean saving, boolean initialized, boolean hasMore, long count, int error) {
        this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
        this.settings = settings;
        this.summary = summary;
        this.forecast = forecast;
        this.loading = loading;
        this.saving = saving;
        this.initialized = initialized;
        this.hasMore = hasMore;
        this.count = count;
        this.error = error;
    }
}
