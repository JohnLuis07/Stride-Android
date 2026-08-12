package com.stride.android;

import android.view.View;
import android.widget.LinearLayout;

/** Contract shared by the XML-backed workspace feature screens. */
interface FeatureScreen {
    View root();
    LinearLayout content();
}
