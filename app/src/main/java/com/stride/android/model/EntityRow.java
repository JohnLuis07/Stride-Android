package com.stride.android.model;

/** Immutable display data for a workspace list row. */
public final class EntityRow {
    public final String id;
    public final String title;
    public final String subtitle;

    public EntityRow(String id, String title, String subtitle) {
        this.id = id;
        this.title = title;
        this.subtitle = subtitle;
    }
}
