package com.stride.android.utils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Currency;
import java.util.List;
import java.util.Locale;

/** Two-decimal monetary input and presentation; never uses binary floating-point. */
public final class AllowanceMoney {
    public static final List<String> CURRENCIES = Collections.unmodifiableList(Arrays.asList(
            "PHP", "USD", "EUR", "GBP", "SGD", "AUD", "CAD", "CNY", "INR"));
    private static final BigDecimal MAX = new BigDecimal("999999999999.99");
    private AllowanceMoney() { }

    public static BigDecimal parse(String text, boolean allowZero) {
        char separator = DecimalFormatSymbols.getInstance().getDecimalSeparator();
        StringBuilder normalized = new StringBuilder();
        for (char character : text.trim().toCharArray()) {
            int digit = Character.digit(character, 10);
            if (digit >= 0) normalized.append(digit);
            else normalized.append(character == separator ? '.' : character);
        }
        if (!normalized.toString().matches("[0-9]+(?:\\.[0-9]{0,2})?")) throw new IllegalArgumentException();
        BigDecimal amount = new BigDecimal(normalized.toString()).setScale(2, RoundingMode.UNNECESSARY);
        if (amount.signum() < 0 || (!allowZero && amount.signum() == 0) || amount.compareTo(MAX) > 0) throw new IllegalArgumentException();
        return amount;
    }

    public static String input(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString()
                .replace('.', DecimalFormatSymbols.getInstance().getDecimalSeparator());
    }

    public static String format(BigDecimal amount, String currency) {
        NumberFormat format = NumberFormat.getCurrencyInstance(Locale.getDefault());
        format.setCurrency(Currency.getInstance(currency));
        format.setMinimumFractionDigits(2);
        format.setMaximumFractionDigits(2);
        return format.format(amount);
    }
}
