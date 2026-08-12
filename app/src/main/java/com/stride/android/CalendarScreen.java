package com.stride.android;

import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import com.stride.android.databinding.ScreenCalendarBinding;

final class CalendarScreen implements FeatureScreen {
    private final ScreenCalendarBinding binding;
    CalendarScreen(LayoutInflater inflater) { binding = ScreenCalendarBinding.inflate(inflater); }
    public View root() { return binding.getRoot(); }
    public LinearLayout content() { return binding.contentContainer; }
}
