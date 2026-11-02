package com.stride.android.model;

import org.json.JSONObject;
import java.math.BigDecimal;

/** Account currency, next declared payday and manually reserved funds. */
public final class AllowanceSettings {
    public final String currency;
    public final String nextPayday;
    public final BigDecimal reserved;
    public final long revision;
    public final boolean stored;

    public AllowanceSettings(String currency, String nextPayday, BigDecimal reserved, long revision, boolean stored) {
        this.currency = currency;
        this.nextPayday = nextPayday;
        this.reserved = reserved;
        this.revision = revision;
        this.stored = stored;
    }

    public static AllowanceSettings defaults() { return new AllowanceSettings("PHP", "", BigDecimal.ZERO, 0, false); }

    public boolean sameValues(AllowanceSettings other) {
        return other != null && currency.equals(other.currency) && nextPayday.equals(other.nextPayday)
                && reserved.compareTo(other.reserved) == 0;
    }

    public static AllowanceSettings fromJson(JSONObject row) {
        return new AllowanceSettings(row.optString("currency"), row.optString("next_payday"),
                new BigDecimal(row.optString("reserved_amount")), row.optLong("revision"), true);
    }
}
