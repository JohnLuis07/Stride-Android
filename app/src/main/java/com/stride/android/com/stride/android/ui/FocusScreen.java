package com.stride.android.ui;

import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.RadioButton;

import androidx.activity.ComponentActivity;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.Observer;

import com.stride.android.R;
import com.stride.android.databinding.ScreenFocusBinding;
import com.stride.android.model.FocusState;
import com.stride.android.viewmodel.FocusViewModel;

import java.text.NumberFormat;
import java.util.Locale;

/** Direct binding owner for the timer, static technique choices, and daily goal. */
public final class FocusScreen implements FeatureScreen {
    private final ScreenFocusBinding binding;
    private final ComponentActivity activity;
    private final FocusViewModel viewModel;
    private final Observer<FocusState> observer = this::render;
    private final LifecycleEventObserver lifecycleObserver = (source, event) -> {
        if (event == Lifecycle.Event.ON_RESUME) {
            refreshTimer();
        }
    };
    private final View.OnLayoutChangeListener ringSizeListener =
            (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> sizeRing(right - left);
    private AlertDialog confirmation;
    private boolean renderingTechnique;
    private boolean closed;

    public FocusScreen(ComponentActivity activity, FocusViewModel viewModel) {
        binding = ScreenFocusBinding.inflate(activity.getLayoutInflater());
        this.activity = activity;
        this.viewModel = viewModel;
    }

    @Override
    public View root() {
        return binding.getRoot();
    }

    public void bind() {
        styleTechnique(binding.techniquePomodoro);
        styleTechnique(binding.technique5217);
        styleTechnique(binding.techniqueFlowtime);
        binding.timerFrame.addOnLayoutChangeListener(ringSizeListener);
        binding.startButton.setOnClickListener(v -> viewModel.toggle());
        binding.resetButton.setOnClickListener(v -> requestChange(viewModel::reset, true));
        binding.modeWork.setOnClickListener(v -> requestMode(FocusState.WORK));
        binding.modeShort.setOnClickListener(v -> requestMode(FocusState.SHORT_BREAK));
        binding.modeLong.setOnClickListener(v -> requestMode(FocusState.LONG_BREAK));
        binding.nextButton.setOnClickListener(v -> {
            FocusState state = viewModel.state().getValue();
            if (state != null && state.completed) {
                requestMode(nextMode(state));
            }
        });
        binding.techniqueGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (renderingTechnique) {
                return;
            }
            FocusState state = viewModel.state().getValue();
            if (state == null) {
                return;
            }
            String technique = checkedId == binding.technique5217.getId() ? FocusState.RULE_52
                    : checkedId == binding.techniqueFlowtime.getId() ? FocusState.FLOWTIME : FocusState.POMODORO;
            // Keep the actual selection visible until a running-session switch is confirmed.
            renderTechniqueSelection(state);
            if (!state.technique.equals(technique)) {
                requestChange(() -> viewModel.selectTechnique(technique), false);
            }
        });
        viewModel.state().observe(activity, observer);
        activity.getLifecycle().addObserver(lifecycleObserver);
        viewModel.refresh();
    }

    private void refreshTimer() {
        if (!closed) {
            viewModel.refresh();
        }
    }

    private void render(FocusState state) {
        if (closed) {
            return;
        }
        boolean work = FocusState.WORK.equals(state.mode);
        boolean paused = !state.running && !state.completed && state.remainingMillis < state.durationMillis;
        long seconds = (state.remainingMillis + 999L) / 1000L;
        String clock = String.format(Locale.getDefault(), "%02d:%02d", seconds / 60, seconds % 60);
        int accent = ContextCompat.getColor(activity, accentColor(state));
        ColorStateList tint = ColorStateList.valueOf(accent);
        binding.countdown.setText(clock);
        binding.countdown.setContentDescription(activity.getString(R.string.focus_timer_description,
                activity.getString(modeLabel(state.technique, state.mode)), clock));
        binding.techniqueLabel.setText(techniqueLabel(state.technique));
        binding.timerStatus.setText(state.completed ? R.string.focus_complete : state.running
                ? work ? R.string.focus_running : R.string.focus_resting
                : paused ? R.string.focus_paused : R.string.focus_ready);
        binding.durationLabel.setText(activity.getString(R.string.focus_duration, state.durationMillis / 60_000L));
        binding.timerProgress.setProgressTintList(tint);
        binding.timerProgress.setProgress(state.progress);
        binding.startButton.setBackgroundTintList(tint);
        binding.startButton.setText(state.running ? R.string.focus_pause
                : state.completed ? R.string.focus_restart : paused ? R.string.focus_resume
                : work ? R.string.focus_start_work : R.string.focus_start_break);
        binding.startButton.setCompoundDrawablesRelativeWithIntrinsicBounds(
                state.running ? R.drawable.ic_focus_pause : R.drawable.ic_focus_play, 0, 0, 0);
        renderMode(binding.modeWork, state, FocusState.WORK, accent);
        renderMode(binding.modeShort, state, FocusState.SHORT_BREAK, accent);
        renderMode(binding.modeLong, state, FocusState.LONG_BREAK, accent);
        renderTechniqueSelection(state);
        binding.completionMessage.setVisibility(state.completed ? View.VISIBLE : View.GONE);
        binding.completionMessage.setText(work ? R.string.focus_work_complete : R.string.focus_break_complete);
        binding.nextButton.setVisibility(state.completed ? View.VISIBLE : View.GONE);
        String next = nextMode(state);
        binding.nextButton.setText(FocusState.WORK.equals(next) ? R.string.focus_next_work
                : FocusState.LONG_BREAK.equals(next) ? R.string.focus_next_long : R.string.focus_next_short);
        binding.nextButton.setTextColor(accent);
        NumberFormat numbers = NumberFormat.getIntegerInstance();
        binding.todayCount.setText(numbers.format(state.todaySessions));
        binding.totalCount.setText(numbers.format(state.sessionsCompleted));
        binding.goalProgress.setProgress(Math.min(FocusState.DAILY_GOAL, state.todaySessions));
        String goal = activity.getString(R.string.focus_goal_progress, state.todaySessions, FocusState.DAILY_GOAL);
        binding.goalLabel.setText(goal);
        binding.goalProgress.setContentDescription(goal);
        binding.goalHint.setText(state.todaySessions >= FocusState.DAILY_GOAL
                ? R.string.focus_goal_reached : R.string.focus_goal_hint);
    }

    private void renderMode(Button button, FocusState state, String mode, int accent) {
        boolean selected = state.mode.equals(mode);
        button.setText(modeLabel(state.technique, mode));
        button.setSelected(selected);
        button.setTextColor(selected ? accent : ContextCompat.getColor(activity, R.color.focus_muted));
    }

    private void renderTechniqueSelection(FocusState state) {
        renderingTechnique = true;
        int checkedId = FocusState.RULE_52.equals(state.technique) ? binding.technique5217.getId()
                : FocusState.FLOWTIME.equals(state.technique) ? binding.techniqueFlowtime.getId()
                : binding.techniquePomodoro.getId();
        if (binding.techniqueGroup.getCheckedRadioButtonId() != checkedId) {
            binding.techniqueGroup.check(checkedId);
        }
        renderingTechnique = false;
    }

    private void requestMode(String mode) {
        FocusState state = viewModel.state().getValue();
        if (state != null && !state.mode.equals(mode)) {
            requestChange(() -> viewModel.selectMode(mode), false);
        }
    }

    private void requestChange(Runnable action, boolean reset) {
        FocusState state = viewModel.state().getValue();
        if (closed || state == null || confirmation != null) {
            return;
        }
        if (!state.running) {
            action.run();
            return;
        }
        confirmation = new AlertDialog.Builder(activity)
                .setTitle(reset ? R.string.focus_reset_title : R.string.focus_switch_title)
                .setMessage(reset ? R.string.focus_reset_message : R.string.focus_switch_message)
                .setNegativeButton(R.string.focus_cancel, null)
                .setPositiveButton(reset ? R.string.focus_reset_confirm : R.string.focus_switch_confirm,
                        (dialog, which) -> {
                            if (!closed) {
                                action.run();
                            }
                        })
                .create();
        confirmation.setOnDismissListener(dialog -> confirmation = null);
        confirmation.show();
    }

    private void sizeRing(int availableWidth) {
        int size = Math.min(availableWidth, Math.round(280 * activity.getResources().getDisplayMetrics().density));
        if (size <= 0) {
            return;
        }
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) binding.timerProgress.getLayoutParams();
        if (params.width != size || params.height != size) {
            params.width = size;
            params.height = size;
            params.gravity = Gravity.CENTER;
            binding.timerProgress.setLayoutParams(params);
        }
    }

    private void styleTechnique(RadioButton button) {
        SpannableString text = new SpannableString(button.getText());
        int separator = text.toString().indexOf('\n');
        if (separator >= 0) {
            text.setSpan(new StyleSpan(Typeface.BOLD), 0, separator, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new ForegroundColorSpan(ContextCompat.getColor(activity, R.color.focus_muted)),
                    separator + 1, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        button.setText(text);
    }

    private static String nextMode(FocusState state) {
        if (!FocusState.WORK.equals(state.mode)) {
            return FocusState.WORK;
        }
        return state.sessionsCompleted > 0 && state.sessionsCompleted % 4 == 0
                ? FocusState.LONG_BREAK : FocusState.SHORT_BREAK;
    }

    private static int techniqueLabel(String technique) {
        return FocusState.RULE_52.equals(technique) ? R.string.focus_rule52_name
                : FocusState.FLOWTIME.equals(technique) ? R.string.focus_flowtime_name : R.string.focus_pomodoro_name;
    }

    private static int modeLabel(String technique, String mode) {
        if (FocusState.RULE_52.equals(technique)) {
            return FocusState.SHORT_BREAK.equals(mode) ? R.string.focus_rule_short_label
                    : FocusState.LONG_BREAK.equals(mode) ? R.string.focus_rule_long_label : R.string.focus_rule_work_label;
        }
        if (FocusState.FLOWTIME.equals(technique)) {
            return FocusState.SHORT_BREAK.equals(mode) ? R.string.focus_flow_short_label
                    : FocusState.LONG_BREAK.equals(mode) ? R.string.focus_flow_long_label : R.string.focus_flow_work_label;
        }
        return FocusState.SHORT_BREAK.equals(mode) ? R.string.focus_short_label
                : FocusState.LONG_BREAK.equals(mode) ? R.string.focus_long_label : R.string.focus_work_label;
    }

    private static int accentColor(FocusState state) {
        if (FocusState.SHORT_BREAK.equals(state.mode)) {
            return R.color.focus_short;
        }
        if (FocusState.LONG_BREAK.equals(state.mode)) {
            return R.color.focus_long;
        }
        return FocusState.RULE_52.equals(state.technique) ? R.color.focus_rule52
                : FocusState.FLOWTIME.equals(state.technique) ? R.color.focus_flowtime : R.color.focus_pomodoro;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        viewModel.state().removeObserver(observer);
        activity.getLifecycle().removeObserver(lifecycleObserver);
        binding.timerFrame.removeOnLayoutChangeListener(ringSizeListener);
        binding.techniqueGroup.setOnCheckedChangeListener(null);
        if (confirmation != null) {
            confirmation.dismiss();
        }
    }
}
