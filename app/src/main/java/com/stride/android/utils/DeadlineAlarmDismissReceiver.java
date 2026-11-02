package com.stride.android.utils;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Dismiss sound without marking a task or goal completed. */
public final class DeadlineAlarmDismissReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        DeadlineReminders.get(context).dismissAlarms(
                intent.getStringExtra(DeadlineReminders.EXTRA_OWNER));
    }
}
