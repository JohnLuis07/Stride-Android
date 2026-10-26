package com.stride.android.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A complete list result, including loading and failure states. */
public final class EntityListState {
    public final List<EntityRow> rows;
    public final boolean loading;
    public final String error;

    public EntityListState(List<EntityRow> rows, boolean loading, String error) {
        this.rows = Collections.unmodifiableList(new ArrayList<>(rows));
        this.loading = loading;
        this.error = error;
    }
}
