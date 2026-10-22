package com.stride.android.model;

import android.text.Html;

import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Locale;

/** Keeps the original HTML separate from the plain-text preview and search content. */
public final class Note {
    public final String id;
    public final String title;
    public final String content;
    public final String preview;
    public final boolean hasImages;
    public final long updatedAt;

    public Note(String id, String title, String content, long updatedAt) {
        this.id = id;
        this.title = title;
        this.content = content;
        this.updatedAt = updatedAt;
        preview = plainText(content).replace('\u00A0', ' ').replaceAll("\\s+", " ").trim();
        hasImages = content.matches("(?is).*<img(\\s|/?>).*");
    }

    public static Note fromJson(JSONObject record) {
        return new Note(text(record, "id"), text(record, "title"), text(record, "content"),
                timestamp(text(record, "updated_at")));
    }

    public static String plainText(String content) {
        if (!content.matches("(?is).*<\\s*/?\\s*(p|div|h[1-6]|br|span|strong|b|i|em|u|s|strike|font|code|sub|sup|ol|ul|li|a|img|blockquote|pre)(\\s|/?>).*")) {
            return content;
        }
        return Html.fromHtml(content, Html.FROM_HTML_MODE_COMPACT).toString().replace("\uFFFC", " ");
    }

    private static String text(JSONObject record, String key) {
        return record.isNull(key) ? "" : record.optString(key, "");
    }

    private static long timestamp(String value) {
        try {
            String normalized = value.replaceFirst("\\.\\d+(?=Z|[+-]\\d{2}:?\\d{2}$)", "");
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US);
            format.setLenient(false);
            return format.parse(normalized).getTime();
        } catch (ParseException | NullPointerException exception) {
            return 0;
        }
    }
}
