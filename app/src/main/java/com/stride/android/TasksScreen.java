package com.stride.android;

import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import com.stride.android.databinding.ScreenTasksBinding;

final class TasksScreen implements FeatureScreen {
    private final ScreenTasksBinding binding;
    TasksScreen(LayoutInflater inflater) { binding = ScreenTasksBinding.inflate(inflater); }
    public View root() { return binding.getRoot(); }
    public LinearLayout content() { return binding.contentContainer; }
}
