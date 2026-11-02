package com.stride.android.utils;

import android.Manifest;
import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.util.AtomicFile;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.stride.android.MainActivity;
import com.stride.android.R;
import com.stride.android.model.Goal;
import com.stride.android.model.Task;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/** Device-local reminders for the last synchronized account and deadlines. */
public final class DeadlineReminders {
    public static final String EXTRA_PAGE = "deadline_page";
    public static final String EXTRA_OWNER = "deadline_owner";
    public static final String ACTION_RING = "com.stride.android.RING_DEADLINE";
    private static final String SILENT_CHANNEL = "deadline_reminders_silent";
    private static final long[] OFFSETS = {86400000L, 3600000L, 300000L, 0L};
    private static DeadlineReminders instance;
    private final Context context;
    private final AtomicFile file;
    private JSONObject records = new JSONObject();
    private String owner = "";
    private long revision;
    private long taskRevision;
    private long goalRevision;
    private long scheduledRing;
    private String scheduledRingKey = "";

    public static synchronized DeadlineReminders get(Context context) {
        if (instance == null) instance = new DeadlineReminders(context.getApplicationContext());
        return instance;
    }

    private DeadlineReminders(Context context) {
        this.context = context;
        file = new AtomicFile(new File(context.getNoBackupFilesDir(), "deadline_reminders.json"));
        if (file.getBaseFile().exists()) {
            try {
                JSONObject saved = new JSONObject(new String(file.readFully(), StandardCharsets.UTF_8));
                owner = saved.optString("owner", "");
                JSONObject items = saved.optJSONObject("records");
                if (items != null) records = items;
                scheduledRing = saved.optLong("scheduled_ring", 0);
                scheduledRingKey = saved.optString("scheduled_ring_key", "");
                // A process restart never resumes previously ringing audio by itself.
                for (String key : keys()) {
                    JSONObject record = records.optJSONObject(key);
                    if (record != null) record.put("ringing", false);
                }
            } catch (Exception exception) {
                Log.w("DeadlineReminders", "Cannot restore reminder schedule");
            }
        }
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel silentChannel = new NotificationChannel(SILENT_CHANNEL,
                    context.getString(R.string.reminder_silent_channel), NotificationManager.IMPORTANCE_LOW);
            silentChannel.setSound(null, null);
            silentChannel.enableVibration(false);
            context.getSystemService(NotificationManager.class).createNotificationChannel(silentChannel);
        }
    }

    public synchronized void activate(String userId) {
        if (!owner.equals(userId)) {
            clear();
            owner = userId;
            persist();
        }
    }

    public synchronized void clear() {
        revision++;
        for (String key : keys()) cancelNotification(key);
        records = new JSONObject();
        owner = "";
        context.getSystemService(AlarmManager.class).cancel(alarmIntent());
        context.getSystemService(AlarmManager.class).cancel(ringingIntent(0));
        scheduledRing = 0;
        scheduledRingKey = "";
        context.stopService(new Intent(context, DeadlineAlarmService.class));
        persist();
    }

    /** Refresh on sign-in/resume. Ignore snapshots that race a local mutation or logout. */
    public synchronized void refresh(SupabaseApi api, String userId) {
        if (!owner.equals(userId) || owner.isEmpty()) return;
        long expectedRevision = revision;
        for (int index = 0; index < 2; index++) {
            final int slot = index;
            final long expectedItems = slot == 0 ? taskRevision : goalRevision;
            api.get(index == 0 ? "tasks" : "goals",
                    SupabaseApi.and("select=*", SupabaseApi.eq("user_id", userId)), (rows, error) -> {
                        synchronized (DeadlineReminders.this) {
                            if (error != null || revision != expectedRevision || !owner.equals(userId)) return;
                            if (expectedItems != (slot == 0 ? taskRevision : goalRevision)) return;
                            if (slot == 0) {
                                List<Task> tasks = new ArrayList<>();
                                for (int i = 0; i < rows.length(); i++) {
                                    JSONObject row = rows.optJSONObject(i);
                                    if (row != null) tasks.add(Task.fromJson(row));
                                }
                                syncTasks(userId, tasks);
                            } else {
                                List<Goal> goals = new ArrayList<>();
                                for (int i = 0; i < rows.length(); i++) {
                                    JSONObject row = rows.optJSONObject(i);
                                    if (row != null) goals.add(Goal.fromJson(row));
                                }
                                syncGoals(userId, goals);
                            }
                        }
                    });
        }
    }

    public synchronized void syncTasks(String userId, List<Task> tasks) {
        if (!owner.equals(userId) || owner.isEmpty()) return;
        taskRevision++;
        Set<String> keep = new HashSet<>();
        for (Task task : tasks) {
            String key = "Tasks:" + task.id;
            keep.add(key);
            put(key, "Tasks", task.title, task.dueDate, task.deadlineTime, task.deadlineAt,
                    "done".equals(task.status), task.reminderSound);
        }
        prune("Tasks:", keep);
        reconcile();
    }

    public synchronized void syncGoals(String userId, List<Goal> goals) {
        if (!owner.equals(userId) || owner.isEmpty()) return;
        goalRevision++;
        Set<String> keep = new HashSet<>();
        for (Goal goal : goals) {
            String key = "Goals:" + goal.id;
            keep.add(key);
            put(key, "Goals", goal.title, goal.targetDate, goal.deadlineTime, goal.deadlineAt,
                    goal.completed(), goal.reminderSound);
        }
        prune("Goals:", keep);
        reconcile();
    }

    private void put(String key, String page, String title, String date, String time,
            String deadlineAt, boolean completed, boolean reminderSound) {
        if (completed || date.isEmpty()) {
            remove(key);
            return;
        }
        try {
            long deadline = CalendarDates.deadlineMillis(date, time, deadlineAt);
            JSONObject previous = records.optJSONObject(key);
            int delivered = previous != null && previous.optLong("deadline") == deadline
                    ? previous.optInt("delivered", -1) : -1;
            boolean sameDeadline = previous != null && previous.optLong("deadline") == deadline;
            boolean alarmHandled = sameDeadline
                    ? previous.optBoolean("alarm_handled", deadline <= System.currentTimeMillis())
                    : deadline <= System.currentTimeMillis();
            boolean ringing = sameDeadline && reminderSound && previous.optBoolean("ringing", false);
            if (previous != null && previous.optLong("deadline") != deadline) cancelNotification(key);
            records.put(key, new JSONObject().put("page", page).put("title", title)
                    .put("deadline", deadline).put("delivered", delivered)
                    .put("reminder_sound", reminderSound).put("alarm_handled", alarmHandled)
                    .put("ringing", ringing));
            if (!ringing) DeadlineAlarmService.refreshActive();
        } catch (Exception exception) {
            remove(key);
        }
    }

    private void prune(String prefix, Set<String> keep) {
        for (String key : keys()) {
            if (key.startsWith(prefix) && !keep.contains(key)) remove(key);
        }
    }

    private List<String> keys() {
        List<String> result = new ArrayList<>();
        Iterator<String> iterator = records.keys();
        while (iterator.hasNext()) result.add(iterator.next());
        return result;
    }

    private void remove(String key) {
        records.remove(key);
        cancelNotification(key);
        DeadlineAlarmService.refreshActive();
    }

    private void cancelNotification(String key) {
        NotificationManagerCompat.from(context).cancel("deadline:" + key, 0);
    }

    /** Deliver only the most urgent missed stage, then schedule the next future boundary. */
    public synchronized void reconcile() {
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        alarms.cancel(alarmIntent());
        if (owner.isEmpty()) return;
        long now = System.currentTimeMillis();
        JSONObject pending = records.optJSONObject(scheduledRingKey);
        // A silent reminder/resume at the deadline must not cancel its due ringing broadcast.
        boolean keepDueRing = scheduledRing > 0 && scheduledRing <= now && now - scheduledRing <= 60000L
                && pending != null && pending.optLong("deadline") == scheduledRing
                && pending.optBoolean("reminder_sound", true) && !pending.optBoolean("alarm_handled", true);
        if (!keepDueRing) {
            alarms.cancel(ringingIntent(0));
            scheduledRing = 0;
            scheduledRingKey = "";
        }
        long next = Long.MAX_VALUE;
        long nextRing = Long.MAX_VALUE;
        String nextRingKey = "";
        for (String key : keys()) {
            JSONObject record = records.optJSONObject(key);
            if (record == null) continue;
            long deadline = record.optLong("deadline");
            if (deadline > now && record.optBoolean("reminder_sound", true)
                    && !record.optBoolean("alarm_handled", true) && deadline < nextRing) {
                nextRing = deadline;
                nextRingKey = key;
            }
            int delivered = record.optInt("delivered", -1);
            int current = -1;
            for (int stage = 0; stage < OFFSETS.length; stage++) {
                long when = deadline - OFFSETS[stage];
                if (when <= now) current = stage;
                else if (stage > delivered) next = Math.min(next, when);
            }
            if (current > delivered && notify(key, record, current)) {
                try { record.put("delivered", current); }
                catch (org.json.JSONException ignored) { }
            }
        }
        persist();
        if (!keepDueRing && nextRing != Long.MAX_VALUE && canScheduleExactAlarms()) {
            try {
                JSONObject record = records.optJSONObject(nextRingKey);
                alarms.setAlarmClock(new AlarmManager.AlarmClockInfo(nextRing,
                        openIntent(nextRingKey, record)), ringingIntent(nextRing));
                scheduledRing = nextRing;
                scheduledRingKey = nextRingKey;
                persist();
            } catch (SecurityException exception) {
                Log.w("DeadlineReminders", "Exact alarm access is unavailable");
            }
        }
        if (next == Long.MAX_VALUE) return;
        try {
            if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, alarmIntent());
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, alarmIntent());
            }
        } catch (SecurityException exception) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, alarmIntent());
        }
    }

    private boolean notify(String key, JSONObject record, int stage) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false;
        NotificationManagerCompat notifications = NotificationManagerCompat.from(context);
        if (!notifications.areNotificationsEnabled()) return false;
        String channelId = SILENT_CHANNEL;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = context.getSystemService(NotificationManager.class)
                    .getNotificationChannel(channelId);
            if (channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE) return false;
        }
        PendingIntent open = openIntent(key, record);
        int[] messages = {R.string.reminder_day, R.string.reminder_hour,
                R.string.reminder_minutes, R.string.reminder_overdue};
        String message = context.getString(messages[stage], record.optString("title"));
        try {
            notifications.notify("deadline:" + key, 0, new NotificationCompat.Builder(context, channelId)
                    .setSmallIcon(R.drawable.ic_deadline_notification)
                    .setContentTitle(context.getString("Tasks".equals(record.optString("page"))
                            ? R.string.reminder_task : R.string.reminder_goal))
                    .setContentText(message).setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                    .setContentIntent(open).setAutoCancel(true)
                    .setSilent(true)
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    .setCategory(NotificationCompat.CATEGORY_REMINDER).build());
            return true;
        } catch (SecurityException exception) {
            return false;
        }
    }

    public synchronized boolean canScheduleExactAlarms() {
        return Build.VERSION.SDK_INT < 31
                || context.getSystemService(AlarmManager.class).canScheduleExactAlarms();
    }

    public synchronized void restoreSchedule() {
        // Boot and clock changes invalidate a previously due broadcast's protected window.
        scheduledRing = 0;
        scheduledRingKey = "";
        reconcile();
    }

    private PendingIntent openIntent(String key, JSONObject record) {
        Intent intent = new Intent(context, MainActivity.class)
                .setData(new Uri.Builder().scheme("stride").authority("deadline")
                        .appendPath(owner).appendPath(key).build())
                .putExtra(EXTRA_OWNER, owner).putExtra(EXTRA_PAGE, record.optString("page"))
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private PendingIntent ringingIntent(long deadline) {
        return PendingIntent.getBroadcast(context, 1,
                new Intent(context, DeadlineReminderReceiver.class).setAction(ACTION_RING)
                        .putExtra("scheduled_deadline", deadline).putExtra(EXTRA_OWNER, owner),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Only the exact deadline broadcast may initiate sound; refresh/boot never rings old items. */
    public synchronized void ringDeadlines(String expectedOwner, long scheduledDeadline) {
        long now = System.currentTimeMillis();
        if (owner.isEmpty() || !owner.equals(expectedOwner) || !canScheduleExactAlarms()
                || now < scheduledDeadline || now - scheduledDeadline > 60000L) {
            reconcile();
            return;
        }
        boolean ring = false;
        for (String key : keys()) {
            JSONObject record = records.optJSONObject(key);
            if (record == null || record.optLong("deadline") != scheduledDeadline
                    || !record.optBoolean("reminder_sound", true)
                    || record.optBoolean("alarm_handled", true)) continue;
            try {
                record.put("alarm_handled", true).put("ringing", true);
                ring = true;
            } catch (org.json.JSONException ignored) { }
        }
        persist();
        if (ring) {
            try {
                ContextCompat.startForegroundService(context, new Intent(context, DeadlineAlarmService.class)
                        .putExtra(EXTRA_OWNER, owner));
            } catch (RuntimeException exception) {
                Log.w("DeadlineReminders", "Android did not allow the deadline alarm service");
                dismissAlarms(owner);
            }
        }
        reconcile();
    }

    public synchronized List<JSONObject> ringingAlarms(String expectedOwner) {
        List<JSONObject> result = new ArrayList<>();
        if (!owner.equals(expectedOwner) || owner.isEmpty()) return result;
        for (String key : keys()) {
            JSONObject record = records.optJSONObject(key);
            if (record == null || !record.optBoolean("ringing", false)) continue;
            try {
                result.add(new JSONObject(record.toString()).put("key", key));
            } catch (org.json.JSONException ignored) { }
        }
        return result;
    }

    public synchronized void dismissAlarms(String expectedOwner) {
        if (!owner.equals(expectedOwner)) return;
        for (String key : keys()) {
            JSONObject record = records.optJSONObject(key);
            if (record != null) {
                try { record.put("ringing", false); }
                catch (org.json.JSONException ignored) { }
            }
        }
        persist();
        DeadlineAlarmService.refreshActive();
    }

    private PendingIntent alarmIntent() {
        return PendingIntent.getBroadcast(context, 0,
                new Intent(context, DeadlineReminderReceiver.class).setAction("com.stride.android.DEADLINE"),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void persist() {
        FileOutputStream stream = null;
        try {
            byte[] bytes = new JSONObject().put("owner", owner).put("records", records)
                    .put("scheduled_ring", scheduledRing).put("scheduled_ring_key", scheduledRingKey)
                    .toString().getBytes(StandardCharsets.UTF_8);
            stream = file.startWrite();
            stream.write(bytes);
            file.finishWrite(stream);
        } catch (Exception exception) {
            if (stream != null) file.failWrite(stream);
            Log.w("DeadlineReminders", "Cannot persist reminder schedule");
        }
    }
}
