package com.stride.android.utils;

import android.content.Context;
import android.text.format.DateFormat;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.TimeZone;

/** Calendar arithmetic uses local noon so daylight-saving changes do not shift dates. */
public final class CalendarDates {
    private CalendarDates() {
    }

    public static Calendar parse(String key) {
        if (key == null || !key.matches("\\d{4}-\\d{2}-\\d{2}")) {
            throw new IllegalArgumentException("Expected an ISO calendar date");
        }
        Calendar date = new GregorianCalendar();
        date.clear();
        date.setLenient(false);
        date.set(Integer.parseInt(key.substring(0, 4)),
                Integer.parseInt(key.substring(5, 7)) - 1,
                Integer.parseInt(key.substring(8, 10)), 12, 0, 0);
        date.getTimeInMillis();
        return date;
    }

    public static String key(Calendar date) {
        return String.format(Locale.US, "%04d-%02d-%02d", date.get(Calendar.YEAR),
                date.get(Calendar.MONTH) + 1, date.get(Calendar.DAY_OF_MONTH));
    }

    public static String today() {
        return key(Calendar.getInstance());
    }

    public static String month(String dateKey) {
        Calendar date = parse(dateKey);
        date.set(Calendar.DAY_OF_MONTH, 1);
        return key(date);
    }

    public static String shiftMonth(String month, int amount) {
        Calendar date = parse(month);
        date.add(Calendar.MONTH, amount);
        return key(date);
    }

    public static Calendar gridStart(String month) {
        Calendar date = parse(month);
        date.add(Calendar.DAY_OF_MONTH, -(date.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY));
        return date;
    }

    public static int cellCount(String month) {
        Calendar date = parse(month);
        int days = date.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY
                + date.getActualMaximum(Calendar.DAY_OF_MONTH);
        return ((days + 6) / 7) * 7;
    }

    public static String gridEnd(String month) {
        Calendar date = gridStart(month);
        date.add(Calendar.DAY_OF_MONTH, cellCount(month) - 1);
        return key(date);
    }

    public static String label(String dateKey, String skeleton) {
        Locale locale = Locale.getDefault();
        String pattern = DateFormat.getBestDateTimePattern(locale, skeleton);
        return new SimpleDateFormat(pattern, locale).format(parse(dateKey).getTime());
    }

    public static String normalizeTime(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (!value.matches("(?:[01]\\d|2[0-3]):[0-5]\\d(?::[0-5]\\d(?:\\.\\d+)?)?")) {
            throw new IllegalArgumentException("Expected a valid time");
        }
        return value.substring(0, 5);
    }

    public static String timeLabel(Context context, String value) {
        String time;
        try {
            time = normalizeTime(value);
        } catch (IllegalArgumentException exception) {
            return value;
        }
        if (time.isEmpty()) {
            return "";
        }
        // These are wall-clock times, independent of today's daylight-saving transition.
        Calendar clock = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        clock.clear();
        clock.set(2000, Calendar.JANUARY, 1, Integer.parseInt(time.substring(0, 2)),
                Integer.parseInt(time.substring(3, 5)), 0);
        String pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(),
                DateFormat.is24HourFormat(context) ? "Hm" : "hm");
        SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.getDefault());
        format.setTimeZone(clock.getTimeZone());
        return format.format(clock.getTime());
    }
}
