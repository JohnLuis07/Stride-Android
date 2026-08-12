package com.stride.android;

import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Button;
import android.widget.TextView;
import com.stride.android.databinding.ScreenSettingsBinding;

final class SettingsScreen implements FeatureScreen {
    private final ScreenSettingsBinding binding;
    SettingsScreen(LayoutInflater inflater) { binding = ScreenSettingsBinding.inflate(inflater); }
    public View root() { return binding.getRoot(); }
    public LinearLayout content() { return binding.contentContainer; }
    TextView email() { return binding.profileEmail; }
    Button editButton() { return binding.editButton; }
    Button featuresButton() { return binding.featuresButton; }
    Button logoutButton() { return binding.logoutButton; }
}
