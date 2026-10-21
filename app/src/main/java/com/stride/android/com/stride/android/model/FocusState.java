package com.stride.android.model;

/** Timer state independent of any activity or view. */
public final class FocusState {
    public final long remainingMillis;
    public final boolean running;
    public final boolean completed;
    public final String technique;
    public final String mode;

    public FocusState(long remainingMillis, boolean running, boolean completed,
            String technique, String mode) {
        this.remainingMillis = remainingMillis;
        this.running = running;
        this.completed = completed;
        this.technique = technique;
        this.mode = mode;
    }
}
