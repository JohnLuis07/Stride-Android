package com.stride.android.model;

import java.math.BigDecimal;

/** A projection, kept separate from received income and paid expenses. */
public final class AllowanceForecast {
    public final String date;
    public final BigDecimal income;
    public final BigDecimal expenses;
    public final BigDecimal balance;
    public final BigDecimal spendable;

    public AllowanceForecast(String date, BigDecimal income, BigDecimal expenses, BigDecimal balance, BigDecimal spendable) {
        this.date = date;
        this.income = income;
        this.expenses = expenses;
        this.balance = balance;
        this.spendable = spendable;
    }
}
