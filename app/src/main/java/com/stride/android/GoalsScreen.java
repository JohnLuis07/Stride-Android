package com.stride.android;

import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Button;
import com.stride.android.databinding.ScreenGoalsBinding;

final class GoalsScreen implements FeatureScreen {
    private final ScreenGoalsBinding binding;
    GoalsScreen(LayoutInflater inflater) { binding = ScreenGoalsBinding.inflate(inflater); }
    public View root() { return binding.getRoot(); }
    public LinearLayout content() { return binding.contentContainer; }
    Button addButton() { return binding.addButton; }
    LinearLayout list() { return binding.listContainer; }
}
