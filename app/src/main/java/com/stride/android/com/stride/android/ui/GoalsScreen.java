package com.stride.android.ui;

import android.view.View;

import androidx.activity.ComponentActivity;

import com.stride.android.databinding.ScreenGoalsBinding;
import com.stride.android.utils.FeatureUi;
import com.stride.android.utils.SupabaseApi;

import org.json.JSONObject;

public final class GoalsScreen extends EntityScreen {

    private final ComponentActivity activity;
    private final ScreenGoalsBinding binding;

    public GoalsScreen(
            ComponentActivity activity,
            SupabaseApi api,
            JSONObject user
    ) {
        super(activity, api, user, "goals");
        this.activity = activity;
        binding = ScreenGoalsBinding.inflate(
                activity.getLayoutInflater()
        );
    }

    @Override
    public View root() {
        return binding.getRoot();
    }

    public void bind() {
        bindList(activity, binding.listContainer, binding.emptyGoals, "Could not load goals.");
        load();

        binding.addButton.setOnClickListener(v ->
                binding.goalEditor.setVisibility(View.VISIBLE)
        );

        binding.cancelGoalButton.setOnClickListener(v ->
                binding.goalEditor.setVisibility(View.GONE)
        );

        binding.saveGoalButton.setOnClickListener(v ->
                save()
        );
    }

    private void load() {
        viewModel.load("");
    }

    private void save() {
        String title = binding.goalTitleInput
                .getText()
                .toString()
                .trim();

        if (title.isEmpty()) {
            ui.toast("Enter a goal title");
            return;
        }

        viewModel.insert(
                FeatureUi.obj(
                        "user_id",
                        user.optString("id"),
                        "title",
                        title,
                        "target_value",
                        FeatureUi.number(
                                binding.goalTargetInput,
                                100
                        ),
                        "current_value",
                        0,
                        "unit",
                        binding.goalUnitInput
                                .getText()
                                .toString()
                                .trim(),
                        "updated_at",
                        FeatureUi.now()
                ),
                (r, e) -> {
                    if (closed) {
                        return;
                    }
                    if (e != null) {
                        ui.toast("Could not save goal");
                        return;
                    }

                    binding.goalEditor.setVisibility(
                            View.GONE
                    );

                    load();
                }
        );
    }
}
