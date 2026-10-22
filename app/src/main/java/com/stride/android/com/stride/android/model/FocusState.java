package com.stride.android.model;

/** Immutable timer snapshot, including progress and completed focus-session statistics. */
public final class FocusState {
    public static final String POMODORO = "Pomodoro";
    public static final String RULE_52 = "52/17 rule";
    public static final String FLOWTIME = "Flowtime";
    public static final String WORK = "Work";
    public static final String SHORT_BREAK = "Short break";
    public static final String LONG_BREAK = "Long break";
    public static final int DAILY_GOAL = 8;

    public final long remainingMillis;
    public final long durationMillis;
    public final boolean running;
    public final boolean completed;
    public final String technique;
    public final String mode;
    public final int sessionsCompleted;
    public final int todaySessions;
    public final int progress;

    public FocusState(long remainingMillis, boolean running, boolean completed,
            String technique, String mode, int sessionsCompleted, int todaySessions) {
        this.durationMillis = duration(technique, mode);
        this.remainingMillis = Math.max(0, Math.min(remainingMillis, durationMillis));
        this.running = running;
        this.completed = completed;
        this.technique = technique;
        this.mode = mode;
        this.sessionsCompleted = sessionsCompleted;
        this.todaySessions = todaySessions;
        progress = (int) (10_000L * (durationMillis - this.remainingMillis) / durationMillis);
    }

    public static long duration(String technique, String mode) {
        int minutes;
        if (RULE_52.equals(technique)) {
            minutes = SHORT_BREAK.equals(mode) ? 17 : LONG_BREAK.equals(mode) ? 30 : 52;
        } else if (FLOWTIME.equals(technique)) {
            minutes = SHORT_BREAK.equals(mode) ? 10 : LONG_BREAK.equals(mode) ? 25 : 90;
        } else {
            minutes = SHORT_BREAK.equals(mode) ? 5 : LONG_BREAK.equals(mode) ? 15 : 25;
        }
        return minutes * 60_000L;
    }
}
