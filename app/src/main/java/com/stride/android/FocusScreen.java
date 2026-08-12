package com.stride.android;

import android.view.LayoutInflater;
import android.view.View;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.CountDownTimer;
import android.widget.LinearLayout;
import android.widget.Button;
import android.widget.TextView;
import com.stride.android.databinding.ScreenFocusBinding;

final class FocusScreen implements FeatureScreen {
    private final ScreenFocusBinding binding;
    private Activity activity; private SharedPreferences prefs; private CountDownTimer timer; private long timeLeft;
    FocusScreen(LayoutInflater inflater) { binding = ScreenFocusBinding.inflate(inflater); }
    FocusScreen(Activity activity, SharedPreferences prefs, long timeLeft) { binding=ScreenFocusBinding.inflate(activity.getLayoutInflater());this.activity=activity;this.prefs=prefs;this.timeLeft=timeLeft; }
    public View root() { return binding.getRoot(); }
    public LinearLayout content() { return binding.contentContainer; }
    TextView countdown() { return binding.countdown; }
    TextView modeLabel() { return binding.modeLabel; }
    Button startButton() { return binding.startButton; }
    Button resetButton() { return binding.resetButton; }
    Button modesButton() { return binding.modesButton; }
    void bind() { TextView countdown=countdown(),label=modeLabel(); Button start=startButton(); countdown.setText(format(timeLeft));label.setText(prefs.getString("focus_tech","Pomodoro")+" · "+prefs.getString("focus_mode","Work"));start.setOnClickListener(v->{if(timer!=null){timer.cancel();timer=null;start.setText("Resume");return;}start.setText("Pause");timer=new CountDownTimer(timeLeft,1000){public void onTick(long ms){timeLeft=ms;countdown.setText(format(ms));}public void onFinish(){timeLeft=0;timer=null;start.setText("Start again");countdown.setText("Done!");prefs.edit().putInt("focus_sessions",prefs.getInt("focus_sessions",0)+1).apply();}}.start();});resetButton().setOnClickListener(v->{if(timer!=null)timer.cancel();timer=null;timeLeft=duration();countdown.setText(format(timeLeft));start.setText("Start");});modesButton().setOnClickListener(v->chooseTechnique(label,countdown,start)); }
    void save() { prefs.edit().putLong("focus_time",timeLeft).apply(); }
    void stop() { if(timer!=null)timer.cancel(); }
    private void chooseTechnique(TextView label,TextView countdown,Button start){new AlertDialog.Builder(activity).setTitle("Focus technique").setItems(new String[]{"Pomodoro (25/5/15)","52/17 rule","Flowtime (90/10/25)"},(d,w)->{String[] names={"Pomodoro","52/17 rule","Flowtime"};prefs.edit().putString("focus_tech",names[w]).putString("focus_mode","Work").apply();timeLeft=duration();label.setText(names[w]+" · Work");countdown.setText(format(timeLeft));start.setText("Start");}).show();}
    private long duration(){String technique=prefs.getString("focus_tech","Pomodoro");return(technique.equals("52/17 rule")?52:technique.equals("Flowtime")?90:25)*60_000L;}
    private String format(long ms){long seconds=Math.max(0,ms)/1000;return String.format(java.util.Locale.US,"%02d:%02d",seconds/60,seconds%60);}
}
