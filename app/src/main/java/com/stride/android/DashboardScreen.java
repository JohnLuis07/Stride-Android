package com.stride.android;

import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Button;
import android.widget.TextView;
import com.stride.android.databinding.ScreenDashboardBinding;
import org.json.JSONObject;

final class DashboardScreen implements FeatureScreen {
    private final ScreenDashboardBinding binding;
    DashboardScreen(LayoutInflater inflater) { binding = ScreenDashboardBinding.inflate(inflater); }
    DashboardScreen(android.app.Activity activity, JSONObject user) { binding = ScreenDashboardBinding.inflate(activity.getLayoutInflater()); this.user = user; }
    private JSONObject user;
    public View root() { return binding.getRoot(); }
    public LinearLayout content() { return binding.contentContainer; }
    TextView title() { return binding.pageTitle; }
    LinearLayout notesCard() { return binding.notesCard; }
    LinearLayout calendarCard() { return binding.calendarCard; }
    LinearLayout tasksCard() { return binding.tasksCard; }
    LinearLayout goalsCard() { return binding.goalsCard; }
    LinearLayout focusCard() { return binding.focusCard; }
    Button notesButton() { return binding.openNotes; }
    Button calendarButton() { return binding.openCalendar; }
    Button tasksButton() { return binding.openTasks; }
    Button goalsButton() { return binding.openGoals; }
    Button focusButton() { return binding.openFocus; }
    void bind(Runnable notes, Runnable calendar, Runnable tasks, Runnable goals, Runnable focus) {
        String features = user.optString("features", "");
        String name = user.optString("full_name", "there").trim();
        title().setText("Welcome back, " + (name.isEmpty() ? "there" : name.split("\\s+")[0]) + "!");
        notesCard().setVisibility(visible(features,"Notes") ? View.VISIBLE : View.GONE);
        calendarCard().setVisibility(visible(features,"Calendar") ? View.VISIBLE : View.GONE);
        tasksCard().setVisibility(visible(features,"Todo") ? View.VISIBLE : View.GONE);
        goalsCard().setVisibility(visible(features,"Goals") ? View.VISIBLE : View.GONE);
        focusCard().setVisibility(View.VISIBLE);
        notesButton().setOnClickListener(v -> notes.run()); calendarButton().setOnClickListener(v -> calendar.run()); tasksButton().setOnClickListener(v -> tasks.run()); goalsButton().setOnClickListener(v -> goals.run()); focusButton().setOnClickListener(v -> focus.run());
    }
    private boolean visible(String features, String feature) { return features.isEmpty() || features.contains(feature); }
}
