package com.stride.android;

import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Button;
import android.widget.EditText;
import com.stride.android.databinding.ScreenCalendarBinding;

final class CalendarScreen implements FeatureScreen {
    private final ScreenCalendarBinding binding;
    CalendarScreen(LayoutInflater inflater) { binding = ScreenCalendarBinding.inflate(inflater); }
    public View root() { return binding.getRoot(); }
    public LinearLayout content() { return binding.contentContainer; }
    EditText date() { return binding.dateInput; }
    Button addButton() { return binding.addButton; }
    LinearLayout list() { return binding.listContainer; }
}
