package com.stride.android;

import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Button;
import android.widget.TextView;
import com.stride.android.databinding.ScreenFocusBinding;

final class FocusScreen implements FeatureScreen {
    private final ScreenFocusBinding binding;
    FocusScreen(LayoutInflater inflater) { binding = ScreenFocusBinding.inflate(inflater); }
    public View root() { return binding.getRoot(); }
    public LinearLayout content() { return binding.contentContainer; }
    TextView countdown() { return binding.countdown; }
    TextView modeLabel() { return binding.modeLabel; }
    Button startButton() { return binding.startButton; }
    Button resetButton() { return binding.resetButton; }
    Button modesButton() { return binding.modesButton; }
}
