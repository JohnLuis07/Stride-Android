package com.stride.android.model;

import java.math.BigDecimal;

/** Immutable result of allowance arithmetic. Daily is null until a future payday is declared. */
public final class AllowanceSummary {
    public final BigDecimal income;
    public final BigDecimal expenses;
    public final BigDecimal balance;
    public final BigDecimal reserved;
    public final BigDecimal spendable;
    public final BigDecimal daily;
    public final int days;

    public AllowanceSummary(BigDecimal income, BigDecimal expenses, BigDecimal balance, BigDecimal reserved,
            BigDecimal spendable, BigDecimal daily, int days) {
        this.income = income;
        this.expenses = expenses;
        this.balance = balance;
        this.reserved = reserved;
        this.spendable = spendable;
        this.daily = daily;
        this.days = days;
    }
}
