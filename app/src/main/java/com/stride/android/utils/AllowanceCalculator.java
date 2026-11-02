package com.stride.android.utils;

import com.stride.android.model.AllowanceSettings;
import com.stride.android.model.AllowanceSummary;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;
import java.util.Date;

/** Pure money and calendar-date arithmetic. No UI, network or clock reads. */
public final class AllowanceCalculator {
    private AllowanceCalculator() { }

    public static AllowanceSummary calculate(BigDecimal income, BigDecimal expenses, AllowanceSettings settings, String today) {
        BigDecimal balance = income.subtract(expenses);
        BigDecimal spendable = balance.subtract(settings.reserved);
        int days = settings.nextPayday.isEmpty() ? 0 : Math.max(0, daysBetween(today, settings.nextPayday));
        BigDecimal daily = days == 0 ? null : spendable.max(BigDecimal.ZERO)
                .divide(BigDecimal.valueOf(days), 2, RoundingMode.DOWN);
        return new AllowanceSummary(income, expenses, balance, settings.reserved, spendable, daily, days);
    }

    public static int daysBetween(String first, String second) {
        return (int) ((day(second) - day(first)) / 86400000L);
    }

    private static long day(String value) {
        if (value == null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new IllegalArgumentException();
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        format.setLenient(false);
        ParsePosition position = new ParsePosition(0);
        Date parsed = format.parse(value, position);
        if (parsed == null || position.getIndex() != value.length()) throw new IllegalArgumentException();
        return parsed.getTime();
    }
}
