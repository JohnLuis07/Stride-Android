package com.stride.android;

import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import com.stride.android.databinding.ScreenDashboardBinding;

final class DashboardScreen implements FeatureScreen {
    private final ScreenDashboardBinding binding;
    DashboardScreen(LayoutInflater inflater) { binding = ScreenDashboardBinding.inflate(inflater); }
    public View root() { return binding.getRoot(); }
    public LinearLayout content() { return binding.contentContainer; }
}
