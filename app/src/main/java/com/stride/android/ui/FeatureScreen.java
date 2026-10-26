package com.stride.android.ui;

import android.view.View;

/** A binding-backed screen hosted by the main activity. */
public interface FeatureScreen {
    View root();

    default void close() {
        // Override to detach observers when navigation replaces this screen.
    }
}
