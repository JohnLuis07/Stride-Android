package com.stride.android.model;

import org.json.JSONObject;
import java.math.BigDecimal;

/** One immutable, manually declared income or expense. */
public final class AllowanceEntry {
    public final String id;
    public final String kind;
    public final String currency;
    public final BigDecimal amount;
    public final String category;
    public final String note;
    public final String date;
    public final long revision;
    public final String status;
    public final String source;

    public AllowanceEntry(String id, String kind, String currency, BigDecimal amount, String category, String note, String date, long revision) {
        this(id, kind, currency, amount, category, note, date, revision, "posted", "manual");
    }

    public AllowanceEntry(String id, String kind, String currency, BigDecimal amount, String category, String note, String date,
            long revision, String status, String source) {
        this.id = id;
        this.kind = kind;
        this.currency = currency;
        this.amount = amount;
        this.category = category;
        this.note = note;
        this.date = date;
        this.revision = revision;
        this.status = status;
        this.source = source;
    }

    public boolean income() { return "income".equals(kind); }
    public boolean planned() { return "planned".equals(status); }
    public boolean salary() { return "salary".equals(source); }

    public boolean sameValues(AllowanceEntry other) {
        return other != null && id.equals(other.id) && kind.equals(other.kind) && currency.equals(other.currency) && amount.compareTo(other.amount) == 0
                && category.equals(other.category) && note.equals(other.note) && date.equals(other.date)
                && status.equals(other.status) && source.equals(other.source);
    }

    public static AllowanceEntry fromJson(JSONObject row) {
        return new AllowanceEntry(row.optString("id"), row.optString("kind"), row.optString("currency"), new BigDecimal(row.optString("amount")),
                row.optString("category"), row.optString("note"), row.optString("occurred_on"), row.optLong("revision"),
                row.optString("entry_status", "posted"), row.optString("source", "manual"));
    }
}
