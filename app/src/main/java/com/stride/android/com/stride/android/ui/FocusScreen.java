package com.stride.android.ui;

import android.view.View;

import androidx.activity.ComponentActivity;
import androidx.lifecycle.Observer;

import com.stride.android.databinding.ScreenFocusBinding;
import com.stride.android.model.FocusState;
import com.stride.android.viewmodel.FocusViewModel;

import java.util.Locale;

/** Binding owner for the focus timer and technique editor. */
public final class FocusScreen implements FeatureScreen {
    private final ScreenFocusBinding binding;
    private final ComponentActivity activity;
    private final FocusViewModel viewModel;
    private Observer<FocusState> observer;

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
        observer = state -> {
            long seconds = Math.max(0, state.remainingMillis) / 1000;
            binding.countdown.setText(state.completed ? "Done!"
                    : String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60));
            binding.modeLabel.setText(state.technique + " \u00b7 " + state.mode);
            binding.startButton.setText(state.running ? "Pause"
                    : state.completed ? "Start again" : "Start");
        };
        viewModel.state().observe(activity, observer);
        binding.startButton.setOnClickListener(v -> viewModel.toggle());
        binding.resetButton.setOnClickListener(v -> viewModel.reset());
        binding.modesButton.setOnClickListener(v -> showTechniqueEditor());
        binding.cancelTechniqueButton.setOnClickListener(v ->
                binding.techniqueEditor.setVisibility(View.GONE));
        binding.saveTechniqueButton.setOnClickListener(v -> {
            int checked = binding.techniqueGroup.getCheckedRadioButtonId();
            String technique = checked == binding.technique5217.getId() ? "52/17 rule"
                    : checked == binding.techniqueFlowtime.getId() ? "Flowtime" : "Pomodoro";
            viewModel.selectTechnique(technique);
            binding.techniqueEditor.setVisibility(View.GONE);
        });
    }

    private void showTechniqueEditor() {
        FocusState state = viewModel.state().getValue();
        if (state == null) {
            return;
        }
        if ("52/17 rule".equals(state.technique)) {
            binding.technique5217.setChecked(true);
        } else if ("Flowtime".equals(state.technique)) {
            binding.techniqueFlowtime.setChecked(true);
        } else {
            binding.techniquePomodoro.setChecked(true);
        }
        binding.techniqueEditor.setVisibility(View.VISIBLE);
    }

    @Override
    public void close() {
        if (observer != null) {
            viewModel.state().removeObserver(observer);
        }
    }
}
