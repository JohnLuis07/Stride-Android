package com.stride.android.utils;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.stride.android.MainActivity;
import com.stride.android.R;

import org.json.JSONObject;

import java.util.List;

/** Keeps a looping deadline alarm audible with the screen off until dismissed. */
public final class DeadlineAlarmService extends Service {
    private static final String CHANNEL = "deadline_alarm_clock";
    private static final int NOTIFICATION_ID = 4102;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile DeadlineAlarmService running;
    private String owner = "";
    private MediaPlayer player;
    private AudioManager audio;
    private AudioFocusRequest focusRequest;
    private boolean hasAudioFocus;
    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> {
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            finishAlarm();
        }
    };

    /** Changes to one record stop its alarm while retaining other simultaneously due alarms. */
    public static void refreshActive() {
        MAIN.post(() -> {
            DeadlineAlarmService service = running;
            if (service != null) service.refreshNotification();
        });
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = this;
        audio = getSystemService(AudioManager.class);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL,
                    getString(R.string.deadline_alarm_channel), NotificationManager.IMPORTANCE_HIGH);
            // MediaPlayer supplies alarm audio; the channel must not add a second sound.
            channel.setSound(null, null);
            channel.enableVibration(false);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        owner = intent.getStringExtra(DeadlineReminders.EXTRA_OWNER);
        List<JSONObject> alarms = DeadlineReminders.get(this).ringingAlarms(owner);
        boolean notificationsAllowed = Build.VERSION.SDK_INT < 33
                || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        == PackageManager.PERMISSION_GRANTED;
        notificationsAllowed &= NotificationManagerCompat.from(this).areNotificationsEnabled();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = getSystemService(NotificationManager.class).getNotificationChannel(CHANNEL);
            notificationsAllowed &= channel != null && channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
        }
        // Never start indefinite audio if Android would hide its Dismiss control.
        if (alarms.isEmpty() || !notificationsAllowed) {
            if (!notificationsAllowed) Log.w("DeadlineAlarm", "Enable notifications to use the alarm Dismiss action");
            stopSelf();
            return START_NOT_STICKY;
        }
        Notification notification = notification(alarms);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        if (player == null) playAlarm();
        return START_NOT_STICKY;
    }

    private void refreshNotification() {
        if (owner == null || owner.isEmpty()) return;
        List<JSONObject> alarms = DeadlineReminders.get(this).ringingAlarms(owner);
        if (alarms.isEmpty()) {
            finishAlarm();
        } else {
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(alarms));
        }
    }

    private Notification notification(List<JSONObject> alarms) {
        JSONObject first = alarms.get(0);
        Intent open = new Intent(this, MainActivity.class)
                .setData(new Uri.Builder().scheme("stride").authority("ringing")
                        .appendPath(owner).appendPath(first.optString("key")).build())
                .putExtra(DeadlineReminders.EXTRA_OWNER, owner)
                .putExtra(DeadlineReminders.EXTRA_PAGE, first.optString("page"))
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPage = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent dismiss = new Intent(this, DeadlineAlarmDismissReceiver.class)
                .setData(new Uri.Builder().scheme("stride").authority("dismiss-alarm").appendPath(owner).build())
                .putExtra(DeadlineReminders.EXTRA_OWNER, owner);
        PendingIntent dismissAlarm = PendingIntent.getBroadcast(this, 0, dismiss,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        StringBuilder details = new StringBuilder();
        for (JSONObject alarm : alarms) {
            if (details.length() > 0) details.append('\n');
            details.append(getString(R.string.deadline_alarm_due, alarm.optString("title")));
        }
        return new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_deadline_notification)
                .setContentTitle(getString(R.string.deadline_alarm_title))
                .setContentText(getString(R.string.deadline_alarm_due, first.optString("title")))
                .setStyle(new NotificationCompat.BigTextStyle().bigText(details.toString()))
                .setContentIntent(openPage).setDeleteIntent(dismissAlarm)
                .addAction(0, getString(alarms.size() > 1
                        ? R.string.deadline_alarm_dismiss_all : R.string.deadline_alarm_dismiss), dismissAlarm)
                .setOngoing(true).setAutoCancel(false).setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setCategory(NotificationCompat.CATEGORY_ALARM).build();
    }

    private void playAlarm() {
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        int focus;
        if (Build.VERSION.SDK_INT >= 26) {
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attributes).setOnAudioFocusChangeListener(focusListener, MAIN).build();
            focus = audio.requestAudioFocus(focusRequest);
        } else {
            focus = audio.requestAudioFocus(focusListener, AudioManager.STREAM_ALARM,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
        }
        hasAudioFocus = focus == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        if (!hasAudioFocus) {
            Log.w("DeadlineAlarm", "Alarm audio focus unavailable");
            finishAlarm();
            return;
        }
        try {
            Uri tone = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM);
            if (tone == null) tone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            player = new MediaPlayer();
            player.setAudioAttributes(attributes);
            player.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
            player.setDataSource(this, tone);
            player.setLooping(true);
            player.setOnPreparedListener(prepared -> {
                if (player == prepared) prepared.start();
            });
            player.setOnErrorListener((failed, what, extra) -> {
                Log.w("DeadlineAlarm", "Cannot play the configured alarm tone");
                finishAlarm();
                return true;
            });
            player.prepareAsync();
        } catch (Exception exception) {
            Log.w("DeadlineAlarm", "Cannot initialize alarm audio");
            finishAlarm();
        }
    }

    private void finishAlarm() {
        releaseAudio();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void releaseAudio() {
        if (player != null) {
            player.release();
            player = null;
        }
        if (hasAudioFocus) {
            hasAudioFocus = false;
            if (Build.VERSION.SDK_INT >= 26 && focusRequest != null) audio.abandonAudioFocusRequest(focusRequest);
            else audio.abandonAudioFocus(focusListener);
        }
    }

    @Override
    public void onDestroy() {
        running = null;
        releaseAudio();
        DeadlineReminders.get(this).dismissAlarms(owner);
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
