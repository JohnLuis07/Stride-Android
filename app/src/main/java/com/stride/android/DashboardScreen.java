package com.stride.android;

import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Button;
import android.widget.TextView;
import com.stride.android.databinding.ScreenDashboardBinding;

final class DashboardScreen implements FeatureScreen {
    private final ScreenDashboardBinding binding;
    DashboardScreen(LayoutInflater inflater) { binding = ScreenDashboardBinding.inflate(inflater); }
    public View root() { return binding.getRoot(); }
    public LinearLayout content() { return binding.contentContainer; }
    TextView title() { return binding.pageTitle; }
    LinearLayout notesCard() { return binding.notesCard; }
    LinearLayout calendarCard() { return binding.calendarCard; }
    LinearLayout tasksCard() { return binding.tasksCard; }
    LinearLayout goalsCard() { return binding.goalsCard; }
    Button notesButton() { return binding.openNotes; }
    Button calendarButton() { return binding.openCalendar; }
    Button tasksButton() { return binding.openTasks; }
    Button goalsButton() { return binding.openGoals; }
    Button focusButton() { return binding.openFocus; }
}
