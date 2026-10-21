package com.stride.android.ui;

import android.view.View;

import androidx.activity.ComponentActivity;

import com.stride.android.databinding.ScreenTasksBinding;
import com.stride.android.utils.FeatureUi;
import com.stride.android.utils.SupabaseApi;

import org.json.JSONObject;

public final class TasksScreen extends EntityScreen {

    private final ComponentActivity activity;
    private final ScreenTasksBinding binding;

    public TasksScreen(
            ComponentActivity activity,
            SupabaseApi api,
            JSONObject user
    ) {
        super(activity, api, user, "tasks");
        this.activity = activity;
        binding = ScreenTasksBinding.inflate(
                activity.getLayoutInflater()
        );
    }

    @Override
    public View root() {
        return binding.getRoot();
    }

    public void bind() {
        bindList(activity, binding.listContainer, binding.emptyTasks, "Could not load tasks.");
        load();

        binding.addButton.setOnClickListener(
                v -> binding.taskEditor.setVisibility(View.VISIBLE)
        );

        binding.cancelTaskButton.setOnClickListener(
                v -> binding.taskEditor.setVisibility(View.GONE)
        );

        binding.saveTaskButton.setOnClickListener(
                v -> save()
        );
    }

    private void load() {
        viewModel.load("");
    }

    private void save() {
        String title = binding.taskTitleInput
                .getText()
                .toString()
                .trim();

        if (title.isEmpty()) {
            ui.toast("Enter a task title");
            return;
        }

        viewModel.insert(
                FeatureUi.obj(
                        "user_id",
                        user.optString("id"),
                        "title",
                        title,
                        "details",
                        FeatureUi.emptyNull(
                                binding.taskDetailsInput
                        ),
                        "due_date",
                        FeatureUi.emptyNull(
                                binding.taskDueInput
                        ),
                        "priority",
                        binding.taskPriorityInput
                                .getText()
                                .toString()
                                .trim(),
                        "status",
                        "todo",
                        "updated_at",
                        FeatureUi.now()
                ),
                (r, e) -> {
                    if (closed) {
                        return;
                    }
                    if (e != null) {
                        ui.toast("Could not save task");
                        return;
                    }

                    binding.taskEditor.setVisibility(
                            View.GONE
                    );

                    binding.taskTitleInput.setText("");
                    binding.taskDetailsInput.setText("");

                    load();
                }
        );
    }
}
