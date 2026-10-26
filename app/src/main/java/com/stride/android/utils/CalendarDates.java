package com.stride.android.utils;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;

import com.stride.android.R;

import java.text.NumberFormat;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Calendar arithmetic uses local noon so daylight-saving changes do not shift dates. */
public final class CalendarDates {
    private static final Pattern TIMESTAMP = Pattern.compile(
            "^([0-9]{4}-[0-9]{2}-[0-9]{2})[Tt ]([0-9]{2}:[0-9]{2}:[0-9]{2})"
                    + "(?:[.]([0-9]{1,9}))?(Z|[+-][0-9]{2}(?::?[0-9]{2})?)$");

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

    /** A date-only deadline remains on track until the next local calendar day. */
    public static boolean isOverdue(String dateKey) {
        parse(dateKey);
        return dateKey.compareTo(today()) < 0;
    }

    public static boolean isOverdue(String dateKey, String deadlineAt) {
        return isOverdue(dateKey, "", deadlineAt);
    }

    public static boolean isOverdue(String dateKey, String deadlineTime, String deadlineAt) {
        try {
            return System.currentTimeMillis() >= deadlineMillis(dateKey, deadlineTime, deadlineAt);
        } catch (IllegalArgumentException exception) {
            // Keep legacy dates usable; an invalid recorded timestamp has no timing result.
            return System.currentTimeMillis() >= deadlineMillis(dateKey, deadlineTime, "");
        }
    }

    public static void validateDeadline(String dateKey, String deadlineTime) {
        if (!dateKey.isEmpty()) {
            parse(dateKey);
        }
        if (!normalizeTime(deadlineTime).isEmpty() && dateKey.isEmpty()) {
            throw new IllegalArgumentException("A deadline time requires a date");
        }
    }

    public static String deadlineLabel(Context context, String dateKey, String deadlineTime, String skeleton) {
        String date = label(dateKey, skeleton);
        return deadlineTime.isEmpty() ? date
                : context.getString(R.string.calendar_date_time, date, timeLabel(context, deadlineTime));
    }

    public static String deadlineTimezone(String dateKey, String previousDate, String previousTimezone) {
        if (dateKey.isEmpty()) {
            return "";
        }
        return dateKey.equals(previousDate) && !previousTimezone.isEmpty()
                ? previousTimezone : TimeZone.getDefault().getID();
    }

    private static long deadlineMillis(String dateKey, String deadlineTime, String deadlineAt) {
        if (!deadlineAt.isEmpty()) {
            return timestampMillis(deadlineAt);
        }
        // Older unfinished records use the device's local date until their timezone is saved.
        Calendar deadline = parse(dateKey);
        deadline.setLenient(true);
        String time = normalizeTime(deadlineTime);
        if (time.isEmpty()) {
            deadline.add(Calendar.DAY_OF_MONTH, 1);
        }
        deadline.set(Calendar.HOUR_OF_DAY, time.isEmpty() ? 0 : Integer.parseInt(time.substring(0, 2)));
        deadline.set(Calendar.MINUTE, time.isEmpty() ? 0 : Integer.parseInt(time.substring(3, 5)));
        deadline.set(Calendar.SECOND, deadlineTime.length() >= 8 ? Integer.parseInt(deadlineTime.substring(6, 8)) : 0);
        String fraction = deadlineTime.contains(".") ? deadlineTime.substring(deadlineTime.indexOf('.') + 1) : "";
        deadline.set(Calendar.MILLISECOND, Integer.parseInt((fraction + "000").substring(0, 3)));
        return deadline.getTimeInMillis();
    }

    private static long timestampMillis(String value) {
        Matcher match = TIMESTAMP.matcher(value);
        if (!match.matches()) {
            throw new IllegalArgumentException("Expected a timestamp with a UTC offset");
        }
        String fraction = match.group(3);
        fraction = ((fraction == null ? "" : fraction) + "000").substring(0, 3);
        String offset = match.group(4);
        if (offset.length() == 3) {
            offset += ":00";
        } else if (offset.length() == 5) {
            offset = offset.substring(0, 3) + ":" + offset.substring(3);
        }
        String normalized = match.group(1) + "T" + match.group(2) + "." + fraction + offset;
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US);
        format.setLenient(false);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        ParsePosition position = new ParsePosition(0);
        Date parsed = format.parse(normalized, position);
        if (parsed == null || position.getIndex() != normalized.length()) {
            throw new IllegalArgumentException("Invalid timestamp");
        }
        return parsed.getTime();
    }

    public static final class Timing {
        public final String text;
        public final boolean late;
        public final boolean available;

        private Timing(String text, boolean late, boolean available) {
            this.text = text;
            this.late = late;
            this.available = available;
        }
    }

    public static Timing timing(Context context, String dateKey, String deadlineAt,
            String completedAt, String completedDeadlineAt, boolean completed) {
        return timing(context, dateKey, "", deadlineAt, completedAt, completedDeadlineAt, completed);
    }

    public static Timing timing(Context context, String dateKey, String deadlineTime, String deadlineAt,
            String completedAt, String completedDeadlineAt, boolean completed) {
        if (!completed && dateKey.isEmpty()) {
            return new Timing("", false, false);
        }
        if (completed && completedAt.isEmpty()) {
            return new Timing(context.getString(R.string.calendar_timing_completion_unknown), false, false);
        }
        if (completed && completedDeadlineAt.isEmpty()) {
            return new Timing(context.getString(R.string.calendar_timing_completion_deadline_unknown), false, false);
        }
        try {
            long deadline = completed ? timestampMillis(completedDeadlineAt)
                    : deadlineMillis(dateKey, deadlineTime, deadlineAt);
            long reference = completed ? timestampMillis(completedAt) : System.currentTimeMillis();
            long difference = deadline - reference;
            boolean late = difference < 0 || (!completed && difference == 0);
            String text;
            if (completed && difference == 0) {
                text = context.getString(R.string.calendar_timing_finished_on_time);
            } else {
                int message = completed
                        ? late ? R.string.calendar_timing_finished_late : R.string.calendar_timing_finished_early
                        : late ? R.string.calendar_timing_overdue : R.string.calendar_timing_remaining;
                text = context.getString(message, duration(context, Math.abs(difference)));
            }
            if (completed) {
                boolean changed;
                try {
                    changed = dateKey.isEmpty() || deadline != deadlineMillis(dateKey, deadlineTime, deadlineAt);
                } catch (IllegalArgumentException exception) {
                    changed = true;
                }
                if (changed) {
                    text = context.getString(R.string.calendar_timing_original_deadline, text);
                }
            }
            return new Timing(text, late, true);
        } catch (IllegalArgumentException exception) {
            return new Timing(context.getString(completed ? R.string.calendar_timing_completion_unknown
                    : R.string.calendar_timing_unavailable), false, false);
        }
    }

    private static String duration(Context context, long milliseconds) {
        long totalMinutes = milliseconds / 60_000L;
        if (totalMinutes == 0) {
            return context.getString(R.string.calendar_timing_less_than_minute);
        }
        long[] quantities = {totalMinutes / 1440L, totalMinutes / 60L % 24L, totalMinutes % 60L};
        int[] labels = {R.plurals.calendar_duration_days, R.plurals.calendar_duration_hours,
                R.plurals.calendar_duration_minutes};
        NumberFormat numbers = NumberFormat.getIntegerInstance();
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < quantities.length; i++) {
            if (quantities[i] > 0) {
                if (result.length() > 0) {
                    result.append(' ');
                }
                result.append(context.getResources().getQuantityString(
                        labels[i], (int) quantities[i], numbers.format(quantities[i])));
            }
        }
        return result.toString();
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

    /** Refresh deadlines every minute, on clock changes, and after returning to the app. */
    public static final class DateObserver implements DefaultLifecycleObserver {
        private final Context context;
        private final Lifecycle lifecycle;
        private final Runnable onChanged;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Runnable dateCheck = this::checkDate;
        private final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                checkDate();
            }
        };
        private boolean active;
        private boolean closed;

        public DateObserver(Context context, LifecycleOwner owner, Runnable onChanged) {
            this.context = context.getApplicationContext();
            lifecycle = owner.getLifecycle();
            this.onChanged = onChanged;
            lifecycle.addObserver(this);
        }

        @Override
        public void onStart(@NonNull LifecycleOwner owner) {
            if (closed || active) {
                return;
            }
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_DATE_CHANGED);
            filter.addAction(Intent.ACTION_TIME_CHANGED);
            filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
            active = true;
            checkDate();
        }

        @Override
        public void onStop(@NonNull LifecycleOwner owner) {
            stop();
        }

        @Override
        public void onDestroy(@NonNull LifecycleOwner owner) {
            close();
        }

        private void checkDate() {
            if (!active || closed) {
                return;
            }
            handler.removeCallbacks(dateCheck);
            onChanged.run();
            if (active && !closed) {
                handler.postDelayed(dateCheck,
                        Math.max(1000L, 60_000L - System.currentTimeMillis() % 60_000L));
            }
        }

        private void stop() {
            handler.removeCallbacks(dateCheck);
            if (active) {
                active = false;
                context.unregisterReceiver(receiver);
            }
        }

        public void close() {
            closed = true;
            stop();
            lifecycle.removeObserver(this);
        }
    }
}
