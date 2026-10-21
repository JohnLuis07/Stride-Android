package com.stride.android.viewmodel;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.CountDownTimer;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.stride.android.model.FocusState;

/** Retains timer state across navigation and activity recreation. */
public final class FocusViewModel extends AndroidViewModel {
    private final SharedPreferences preferences;
    private final MutableLiveData<FocusState> state = new MutableLiveData<>();
    private CountDownTimer timer;
    private long timeLeft;
    private boolean completed;

    public FocusViewModel(@NonNull Application application) {
        super(application);
        preferences = application.getSharedPreferences("stride", Context.MODE_PRIVATE);
        timeLeft = preferences.getLong("focus_time", 25 * 60_000L);
        publish();
    }

    public LiveData<FocusState> state() {
        return state;
    }

    public void toggle() {
        if (timer != null) {
            stop();
            return;
        }
        completed = false;
        if (timeLeft <= 0) {
            timeLeft = duration();
        }
        timer = new CountDownTimer(timeLeft, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                timeLeft = millisUntilFinished;
                publish();
            }

            @Override
            public void onFinish() {
                timer = null;
                timeLeft = 0;
                completed = true;
                preferences.edit().putInt("focus_sessions",
                        preferences.getInt("focus_sessions", 0) + 1).apply();
                save();
                publish();
            }
        };
        timer.start();
        publish();
    }

    public void reset() {
        stop();
        completed = false;
        timeLeft = duration();
        save();
        publish();
    }

    public void selectTechnique(String technique) {
        preferences.edit().putString("focus_tech", technique)
                .putString("focus_mode", "Work").apply();
        reset();
    }

    public void stop() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
        save();
        publish();
    }

    public void save() {
        preferences.edit().putLong("focus_time", timeLeft).apply();
    }

    private long duration() {
        String technique = preferences.getString("focus_tech", "Pomodoro");
        return ("52/17 rule".equals(technique) ? 52
                : "Flowtime".equals(technique) ? 90 : 25) * 60_000L;
    }

    private void publish() {
        state.setValue(new FocusState(timeLeft, timer != null, completed,
                preferences.getString("focus_tech", "Pomodoro"),
                preferences.getString("focus_mode", "Work")));
    }

    @Override
    protected void onCleared() {
        save();
        if (timer != null) {
            timer.cancel();
        }
    }
}
