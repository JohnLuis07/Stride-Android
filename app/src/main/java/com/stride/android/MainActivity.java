package com.stride.android;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.text.DateFormat;
import java.util.Date;

public class MainActivity extends android.app.Activity {
    private final int primary = Color.rgb(91, 92, 226);
    private final int ink = Color.rgb(29, 27, 32);
    private LinearLayout content;
    private SharedPreferences preferences;
    private CountDownTimer timer;
    private long timeRemaining = 25 * 60 * 1000L;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences("stride", MODE_PRIVATE);
        showDashboard();
    }

    private void shell(String title) {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Color.rgb(248,248,252));
        LinearLayout bar = new LinearLayout(this); bar.setGravity(Gravity.CENTER_VERTICAL); bar.setPadding(dp(20), dp(16), dp(20), dp(12));
        TextView brand = text("Stride", 26, Color.WHITE); brand.setTypeface(null, 1);
        bar.setBackgroundColor(primary); bar.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        Button menu = button("☰", false); menu.setTextSize(22); menu.setOnClickListener(v -> navigation()); bar.addView(menu, new LinearLayout.LayoutParams(dp(54), dp(46)));
        root.addView(bar); content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(20), dp(18), dp(20), dp(24));
        ScrollView scroll = new ScrollView(this); scroll.addView(content); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1)); setContentView(root);
        TextView heading = text(title, 25, ink); heading.setTypeface(null, 1); content.addView(heading); spacer(12);
    }

    private void showDashboard() {
        shell("Good day");
        content.addView(text("Build momentum, one focused step at a time.", 16, Color.DKGRAY)); spacer(18);
        card("Today's focus", "Choose one meaningful task and give it your full attention.", "Start focus", v -> showFocus());
        spacer(14); card("Tasks", pendingTasks() + " task" + (pendingTasks() == 1 ? "" : "s") + " still open.", "View tasks", v -> showTasks());
        spacer(14); card("Notes", "Capture ideas before they disappear.", "Open notes", v -> showNotes());
        spacer(22); content.addView(text("Today · " + DateFormat.getDateInstance(DateFormat.FULL).format(new Date()), 14, Color.GRAY));
    }

    private void showTasks() {
        shell("Tasks");
        Button add = button("+ Add task", true); add.setOnClickListener(v -> addTask()); content.addView(add); spacer(14);
        String saved = preferences.getString("tasks", "");
        if (saved.isEmpty()) content.addView(text("No tasks yet. Add the first thing you want to finish.", 16, Color.DKGRAY));
        else for (String task : saved.split("\\n")) if (!task.isEmpty()) taskRow(task);
    }

    private void addTask() {
        EditText input = new EditText(this); input.setHint("What needs doing?"); input.setSingleLine();
        new AlertDialog.Builder(this).setTitle("New task").setView(input).setNegativeButton("Cancel", null).setPositiveButton("Add", (d,w) -> {
            String value = input.getText().toString().trim(); if (!value.isEmpty()) preferences.edit().putString("tasks", preferences.getString("tasks", "") + value + "\n").apply(); showTasks();
        }).show();
    }

    private void taskRow(String task) {
        LinearLayout row = new LinearLayout(this); row.setPadding(dp(8),dp(5),dp(8),dp(5)); row.setGravity(Gravity.CENTER_VERTICAL);
        CheckBox done = new CheckBox(this); done.setText(task); done.setTextSize(16); done.setTextColor(ink); done.setOnCheckedChangeListener((b, checked) -> { if (checked) removeTask(task); });
        row.addView(done, new LinearLayout.LayoutParams(0,-2,1)); content.addView(row); spacer(5);
    }

    private void removeTask(String task) { preferences.edit().putString("tasks", preferences.getString("tasks", "").replace(task + "\n", "")).apply(); showTasks(); }
    private int pendingTasks() { String t = preferences.getString("tasks", ""); return t.isEmpty() ? 0 : t.split("\\n").length; }

    private void showFocus() {
        shell("Focus timer");
        TextView countdown = text(formatTime(timeRemaining), 54, primary); countdown.setGravity(Gravity.CENTER); content.addView(countdown, new LinearLayout.LayoutParams(-1, dp(110)));
        content.addView(text("Pomodoro · 25 minutes of distraction-free work", 16, Color.DKGRAY)); spacer(18);
        Button action = button("Start", true); action.setOnClickListener(v -> {
            if (timer != null) { timer.cancel(); timer = null; action.setText("Resume"); return; }
            action.setText("Pause"); timer = new CountDownTimer(timeRemaining, 1000) {
                public void onTick(long ms) { timeRemaining = ms; countdown.setText(formatTime(ms)); }
                public void onFinish() { timeRemaining = 0; countdown.setText("Done!"); action.setText("Start again"); timer = null; }
            }.start();
        }); content.addView(action); spacer(12);
        Button reset = button("Reset timer", false); reset.setOnClickListener(v -> { if(timer != null) timer.cancel(); timer=null; timeRemaining=25*60*1000L; countdown.setText(formatTime(timeRemaining)); action.setText("Start"); }); content.addView(reset);
    }

    private void showNotes() {
        shell("Notes");
        EditText note = new EditText(this); note.setHint("Write something worth remembering…"); note.setGravity(Gravity.TOP); note.setMinLines(8); note.setText(preferences.getString("note", ""));
        content.addView(note); spacer(12); Button save = button("Save note", true); save.setOnClickListener(v -> { preferences.edit().putString("note", note.getText().toString()).apply(); Toast.makeText(this,"Saved",Toast.LENGTH_SHORT).show(); }); content.addView(save);
    }

    private void showGoals() { shell("Goals"); content.addView(text("Set a direction, then keep showing up.", 16, Color.DKGRAY)); spacer(18); card("This week", "Add your top three outcomes here as your plan takes shape.", "View tasks", v -> showTasks()); }
    private void showCalendar() { shell("Calendar"); content.addView(text(DateFormat.getDateInstance(DateFormat.FULL).format(new Date()), 19, ink)); spacer(14); content.addView(text("Your schedule is clear. Use tasks and focus sessions to plan your day.", 16, Color.DKGRAY)); }
    private void showProfile() { shell("Profile"); String name = preferences.getString("name", "Stride user"); content.addView(text(name, 22, ink)); spacer(8); content.addView(text("Your progress is stored on this device.", 16, Color.DKGRAY)); spacer(16); Button rename=button("Edit name",true); rename.setOnClickListener(v->rename()); content.addView(rename); }
    private void rename() { EditText input=new EditText(this); input.setText(preferences.getString("name","")); new AlertDialog.Builder(this).setTitle("Your name").setView(input).setPositiveButton("Save",(d,w)->{preferences.edit().putString("name",input.getText().toString().trim()).apply();showProfile();}).setNegativeButton("Cancel",null).show(); }

    private void navigation() { final String[] pages={"Dashboard","Tasks","Focus","Notes","Goals","Calendar","Profile"}; new AlertDialog.Builder(this).setTitle("Navigate").setItems(pages,(d,w)->{ switch(w){case 0:showDashboard();break;case 1:showTasks();break;case 2:showFocus();break;case 3:showNotes();break;case 4:showGoals();break;case 5:showCalendar();break;default:showProfile();} }).show(); }
    private void card(String title,String body,String action,View.OnClickListener listener) { LinearLayout box=new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(18),dp(16),dp(18),dp(16)); box.setBackground(round(Color.WHITE,18)); TextView h=text(title,19,ink);h.setTypeface(null,1);box.addView(h);spacer(box,6);box.addView(text(body,15,Color.DKGRAY));spacer(box,12);Button b=button(action,true);b.setOnClickListener(listener);box.addView(b);content.addView(box); }
    private TextView text(String value,int size,int color){TextView view=new TextView(this);view.setText(value);view.setTextSize(size);view.setTextColor(color);return view;}
    private Button button(String label,boolean filled){Button view=new Button(this);view.setText(label);view.setTextSize(15);view.setAllCaps(false);view.setTextColor(filled?Color.WHITE:primary);view.setBackground(round(filled?primary:Color.TRANSPARENT,14));return view;}
    private GradientDrawable round(int color,int radius){GradientDrawable shape=new GradientDrawable();shape.setColor(color);shape.setCornerRadius(dp(radius));return shape;}
    private void spacer(int height){spacer(content,height);} private void spacer(LinearLayout parent,int height){Space s=new Space(this);parent.addView(s,new LinearLayout.LayoutParams(1,dp(height)));} private int dp(int value){return (int)(value*getResources().getDisplayMetrics().density+.5f);} private String formatTime(long ms){long s=ms/1000;return String.format("%02d:%02d",s/60,s%60);}
}

