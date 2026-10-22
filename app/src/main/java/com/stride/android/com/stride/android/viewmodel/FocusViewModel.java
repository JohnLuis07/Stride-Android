package com.stride.android.viewmodel;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.stride.android.model.FocusState;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** Deadline-based timer retained across navigation, with recoverable persisted state. */
public final class FocusViewModel extends AndroidViewModel {
    private static final int STATE_VERSION = 2;
    private final SharedPreferences preferences;
    private final MutableLiveData<FocusState> state = new MutableLiveData<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable dayRefresh = this::refreshDay;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            updateRemaining(true);
            if (running) {
                if (rollDay()) {
                    persist();
                }
                publish();
                handler.postDelayed(this, Math.min(1000L, timeLeft));
            }
        }
    };
    private String technique;
    private String mode;
    private String countDate;
    private long timeLeft;
    private long endElapsed;
    private long endWall;
    private int sessionsCompleted;
    private int todaySessions;
    private boolean running;
    private boolean completed;
    private ToneGenerator completionTone;

    public FocusViewModel(@NonNull Application application) {
        super(application);
        preferences = application.getSharedPreferences("stride", Context.MODE_PRIVATE);
        technique = validTechnique(preferences.getString("focus_tech", FocusState.POMODORO));
        mode = validMode(preferences.getString("focus_mode", FocusState.WORK));
        timeLeft = Math.max(0, Math.min(duration(), preferences.getLong("focus_time", duration())));
        sessionsCompleted = Math.max(0, preferences.getInt("focus_sessions", 0));
        countDate = preferences.getString("focus_count_date", day(System.currentTimeMillis()));
        todaySessions = Math.max(0, preferences.getInt("focus_today_sessions", 0));
        rollDay();
        completed = preferences.getBoolean("focus_completed", timeLeft == 0);
        running = preferences.getInt("focus_state_version", 0) >= STATE_VERSION
                && preferences.getBoolean("focus_running", false);
        if (running) {
            long nowElapsed = SystemClock.elapsedRealtime();
            long nowWall = System.currentTimeMillis();
            long savedElapsed = preferences.getLong("focus_saved_elapsed", -1);
            long savedWall = preferences.getLong("focus_saved_wall", 0);
            endWall = preferences.getLong("focus_end_wall", nowWall + timeLeft);
            // Use the monotonic deadline on the same boot; wall time recovers after a reboot.
            boolean sameBoot = savedElapsed >= 0 && nowElapsed >= savedElapsed
                    && Math.abs((nowWall - nowElapsed) - (savedWall - savedElapsed)) < 60_000L;
            long remaining = sameBoot
                    ? preferences.getLong("focus_end_elapsed", nowElapsed) - nowElapsed
                    : endWall - nowWall;
            timeLeft = Math.max(0, Math.min(duration(), remaining));
            endElapsed = nowElapsed + timeLeft;
            completed = false;
            if (timeLeft == 0) {
                complete(false);
            } else {
                handler.postDelayed(tick, Math.min(1000L, timeLeft));
            }
        }
        persist();
        publish();
        scheduleDayRefresh();
    }

    public LiveData<FocusState> state() {
        return state;
    }

    public void toggle() {
        if (running) {
            stop();
            return;
        }
        if (completed || timeLeft <= 0) {
            timeLeft = duration();
        }
        rollDay();
        completed = false;
        running = true;
        endElapsed = SystemClock.elapsedRealtime() + timeLeft;
        endWall = System.currentTimeMillis() + timeLeft;
        persist();
        publish();
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, Math.min(1000L, timeLeft));
    }

    public void reset() {
        updateRemaining(false);
        resetCurrentMode();
    }

    public void selectTechnique(String technique) {
        String next = validTechnique(technique);
        if (this.technique.equals(next)) {
            return;
        }
        updateRemaining(false);
        this.technique = next;
        mode = FocusState.WORK;
        resetCurrentMode();
    }

    public void selectMode(String mode) {
        String next = validMode(mode);
        if (this.mode.equals(next)) {
            return;
        }
        updateRemaining(false);
        this.mode = next;
        resetCurrentMode();
    }

    /** Called when the Focus screen becomes active again, including after device sleep. */
    public void refresh() {
        updateRemaining(true);
        if (rollDay()) {
            persist();
        }
        publish();
        scheduleDayRefresh();
    }

    /** Pauses the timer; MainActivity also uses this when the user signs out. */
    public void stop() {
        updateRemaining(false);
        running = false;
        handler.removeCallbacks(tick);
        rollDay();
        persist();
        publish();
    }

    /** Saves a deadline as well as the visible time, rather than freezing a running timer. */
    public void save() {
        updateRemaining(false);
        rollDay();
        persist();
    }

    private void resetCurrentMode() {
        running = false;
        completed = false;
        handler.removeCallbacks(tick);
        timeLeft = duration();
        endElapsed = 0;
        endWall = 0;
        rollDay();
        persist();
        publish();
    }

    private void updateRemaining(boolean sound) {
        if (!running) {
            return;
        }
        timeLeft = Math.max(0, endElapsed - SystemClock.elapsedRealtime());
        if (timeLeft == 0) {
            complete(sound);
        }
    }

    private void complete(boolean sound) {
        if (!running) {
            return;
        }
        running = false;
        completed = true;
        timeLeft = 0;
        handler.removeCallbacks(tick);
        rollDay();
        if (FocusState.WORK.equals(mode)) {
            if (sessionsCompleted < Integer.MAX_VALUE) {
                sessionsCompleted++;
            }
            // A recovered session ending yesterday must not count towards today's goal.
            if (day(endWall).equals(countDate) && todaySessions < Integer.MAX_VALUE) {
                todaySessions++;
            }
        }
        persist();
        publish();
        if (sound) {
            playCompletionTone();
        }
    }

    private void playCompletionTone() {
        releaseTone();
        try {
            ToneGenerator tone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 75);
            completionTone = tone;
            tone.startTone(ToneGenerator.TONE_PROP_ACK, 800);
            handler.postDelayed(() -> {
                if (completionTone == tone) {
                    releaseTone();
                }
            }, 1000);
        } catch (RuntimeException exception) {
            // Completion and statistics remain available when audio cannot be played.
            releaseTone();
        }
    }

    private void releaseTone() {
        if (completionTone != null) {
            completionTone.release();
            completionTone = null;
        }
    }

    private boolean rollDay() {
        String today = day(System.currentTimeMillis());
        if (!today.equals(countDate)) {
            countDate = today;
            todaySessions = 0;
            return true;
        }
        return false;
    }

    private void refreshDay() {
        if (rollDay()) {
            persist();
            publish();
        }
        scheduleDayRefresh();
    }

    private void scheduleDayRefresh() {
        // Also update a paused screen left open across midnight.
        Calendar nextDay = Calendar.getInstance();
        nextDay.add(Calendar.DAY_OF_MONTH, 1);
        nextDay.set(Calendar.HOUR_OF_DAY, 0);
        nextDay.set(Calendar.MINUTE, 0);
        nextDay.set(Calendar.SECOND, 0);
        nextDay.set(Calendar.MILLISECOND, 0);
        handler.removeCallbacks(dayRefresh);
        handler.postDelayed(dayRefresh, Math.max(1000L, nextDay.getTimeInMillis() - System.currentTimeMillis()));
    }

    private long duration() {
        return FocusState.duration(technique, mode);
    }

    private void persist() {
        preferences.edit().putInt("focus_state_version", STATE_VERSION)
                .putString("focus_tech", technique).putString("focus_mode", mode)
                .putLong("focus_time", timeLeft).putBoolean("focus_running", running)
                .putBoolean("focus_completed", completed).putLong("focus_end_elapsed", endElapsed)
                .putLong("focus_end_wall", endWall).putLong("focus_saved_elapsed", SystemClock.elapsedRealtime())
                .putLong("focus_saved_wall", System.currentTimeMillis())
                .putInt("focus_sessions", sessionsCompleted).putString("focus_count_date", countDate)
                .putInt("focus_today_sessions", todaySessions).apply();
    }

    private void publish() {
        state.setValue(new FocusState(timeLeft, running, completed, technique, mode,
                sessionsCompleted, todaySessions));
    }

    private static String validTechnique(String value) {
        return FocusState.RULE_52.equals(value) || FocusState.FLOWTIME.equals(value)
                ? value : FocusState.POMODORO;
    }

    private static String validMode(String value) {
        return FocusState.SHORT_BREAK.equals(value) || FocusState.LONG_BREAK.equals(value)
                ? value : FocusState.WORK;
    }

    private static String day(long time) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(time));
    }

    @Override
    protected void onCleared() {
        save();
        handler.removeCallbacksAndMessages(null);
        releaseTone();
    }
}
