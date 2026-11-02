package com.stride.android.utils;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Rebuild the next alarm after delivery, reboot, clock changes or permission changes. */
public final class DeadlineReminderReceiver extends BroadcastReceiver {
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    @Override
    public void onReceive(Context context, Intent intent) {
        PendingResult result = goAsync();
        Context application = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            try {
                DeadlineReminders reminders = DeadlineReminders.get(application);
                if (DeadlineReminders.ACTION_RING.equals(intent.getAction())) {
                    reminders.ringDeadlines(intent.getStringExtra(DeadlineReminders.EXTRA_OWNER),
                            intent.getLongExtra("scheduled_deadline", 0));
                } else if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                        || Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())
                        || Intent.ACTION_TIME_CHANGED.equals(intent.getAction())
                        || Intent.ACTION_TIMEZONE_CHANGED.equals(intent.getAction())) {
                    reminders.restoreSchedule();
                } else {
                    reminders.reconcile();
                }
            } finally {
                result.finish();
            }
        });
    }
}
