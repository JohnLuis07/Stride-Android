package com.stride.android.utils;

import com.stride.android.model.AllowanceForecast;
import java.math.BigDecimal;

/** Pure projection from full-ledger aggregates, never from a visible history page. */
public final class AllowanceForecastCalculator {
    private AllowanceForecastCalculator() { }

    public static AllowanceForecast calculate(String date, BigDecimal actualBalance, BigDecimal plannedIncome,
            BigDecimal plannedExpenses, BigDecimal reserved) {
        BigDecimal balance = actualBalance.add(plannedIncome).subtract(plannedExpenses);
        return new AllowanceForecast(date, plannedIncome, plannedExpenses, balance, balance.subtract(reserved));
    }
}
