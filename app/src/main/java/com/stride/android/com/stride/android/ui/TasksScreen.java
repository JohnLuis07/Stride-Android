package com.stride.android.ui;

import android.app.AlertDialog;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.stride.android.R;
import com.stride.android.adapter.TaskAdapter;
import com.stride.android.databinding.ItemTasksHeaderBinding;
import com.stride.android.databinding.ScreenTasksBinding;
import com.stride.android.model.Task;
import com.stride.android.model.TasksState;
import com.stride.android.utils.SupabaseApi;
import com.stride.android.viewmodel.TasksViewModel;

import org.json.JSONObject;

import java.text.NumberFormat;

/** Task workspace with a scrolling overview and binding-backed task cards. */
public final class TasksScreen implements FeatureScreen {
    private final ComponentActivity activity;
    private final ScreenTasksBinding binding;
    private final TasksViewModel viewModel;
    private final Observer<TasksState> observer = this::render;
    private final View.OnLayoutChangeListener contentSizeListener =
            (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                    sizeTaskList(bottom - top - view.getPaddingTop() - view.getPaddingBottom());
    private TaskAdapter taskAdapter;
    private TaskEditor editor;
    private AlertDialog confirmation;
    private boolean closed;

    public TasksScreen(ComponentActivity activity, SupabaseApi api, JSONObject user) {
        this.activity = activity;
        binding = ScreenTasksBinding.inflate(activity.getLayoutInflater());
        viewModel = new ViewModelProvider(activity).get(
                "tasks:" + user.optString("id"), TasksViewModel.class);
        viewModel.configure(api, user.optString("id"));
    }

    @Override
    public View root() {
        return binding.getRoot();
    }

    public void bind() {
        ItemTasksHeaderBinding header = binding.tasksHeader;
        header.addButton.setOnClickListener(v -> showEditor(null));
        header.refreshButton.setOnClickListener(v -> viewModel.reload());
        header.filterAll.setOnClickListener(v -> viewModel.setFilter("all"));
        header.filterTodo.setOnClickListener(v -> viewModel.setFilter("todo"));
        header.filterDoing.setOnClickListener(v -> viewModel.setFilter("doing"));
        header.filterDone.setOnClickListener(v -> viewModel.setFilter("done"));
        taskAdapter = new TaskAdapter(new TaskAdapter.Actions() {
            @Override
            public void onEdit(Task task) {
                showEditor(task);
            }

            @Override
            public void onDelete(Task task) {
                confirmDelete(task);
            }

            @Override
            public void onStatus(Task task, String status) {
                viewModel.changeStatus(task, status, success -> {
                    if (!closed && !success) {
                        toast(R.string.tasks_status_failed);
                    }
                });
            }
        });
        binding.tasksContent.setLayoutManager(new LinearLayoutManager(activity));
        binding.tasksContent.setItemAnimator(null);
        binding.tasksContent.setAdapter(taskAdapter);
        binding.getRoot().addOnLayoutChangeListener(contentSizeListener);
        viewModel.state().observe(activity, observer);
        viewModel.reload();
    }

    private void render(TasksState state) {
        if (closed) {
            return;
        }
        renderOverview(state);
        taskAdapter.setBusy(state.saving || state.loading);
        taskAdapter.submitList(state.visibleTasks);
        binding.tasksContent.setVisibility(state.visibleTasks.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void renderOverview(TasksState state) {
        ItemTasksHeaderBinding header = binding.tasksHeader;
        NumberFormat numbers = NumberFormat.getIntegerInstance();
        header.totalCount.setText(numbers.format(state.total));
        header.todoCount.setText(numbers.format(state.todo));
        header.doingCount.setText(numbers.format(state.doing));
        header.doneCount.setText(numbers.format(state.done));
        int percent = state.total == 0 ? 0 : (int) (100L * state.done / state.total);
        header.progressLabel.setText(activity.getString(R.string.tasks_progress, state.done, state.total));
        header.progressPercent.setText(activity.getString(R.string.tasks_progress_percent, percent));
        header.completionProgress.setProgress(percent);
        header.completionProgress.setContentDescription(header.progressLabel.getText());
        header.addButton.setEnabled(!state.saving && !state.loading);
        header.refreshButton.setEnabled(!state.saving && !state.loading);
        header.refreshButton.setText(state.loadFailed ? R.string.tasks_retry : R.string.tasks_refresh);
        renderFilter(header.filterAll, "all".equals(state.filter));
        renderFilter(header.filterTodo, "todo".equals(state.filter));
        renderFilter(header.filterDoing, "doing".equals(state.filter));
        renderFilter(header.filterDone, "done".equals(state.filter));
        header.resultCount.setText(activity.getString(R.string.tasks_visible_count, state.visibleTasks.size()));
        header.loadingProgress.setVisibility(state.loading || state.saving ? View.VISIBLE : View.GONE);
        boolean feedback = state.loading || state.saving || state.loadFailed;
        header.feedbackText.setVisibility(feedback ? View.VISIBLE : View.GONE);
        header.feedbackText.setText(state.saving ? R.string.tasks_saving
                : state.loading ? R.string.tasks_loading : R.string.tasks_load_failed);
        header.feedbackText.setTextColor(ContextCompat.getColor(activity,
                state.loadFailed ? R.color.tasks_high : R.color.tasks_muted));
        header.emptyState.setVisibility(!feedback && state.visibleTasks.isEmpty() ? View.VISIBLE : View.GONE);
        boolean freshStart = state.total == 0 && "all".equals(state.filter);
        header.emptyTitle.setText(freshStart ? R.string.tasks_empty_title : R.string.tasks_filter_empty_title);
        header.emptyMessage.setText(freshStart ? R.string.tasks_empty_message : R.string.tasks_filter_empty_message);
    }

    private void renderFilter(Button button, boolean selected) {
        button.setSelected(selected);
        button.setTextColor(ContextCompat.getColor(activity,
                selected ? android.R.color.white : R.color.tasks_accent));
    }

    private void sizeTaskList(int viewportHeight) {
        // Account for page spacing so the whole list viewport fits above the bottom gutter.
        if (viewportHeight <= 0) {
            return;
        }
        View page = (View) binding.tasksContent.getParent();
        int listHeight = Math.max(1, viewportHeight - page.getPaddingTop() - page.getPaddingBottom());
        ViewGroup.LayoutParams params = binding.tasksContent.getLayoutParams();
        if (params.height != listHeight) {
            params.height = listHeight;
            binding.tasksContent.setLayoutParams(params);
        }
    }

    private void showEditor(Task task) {
        TasksState state = viewModel.state().getValue();
        if (closed || editor != null || state == null || state.saving || state.loading) {
            return;
        }
        editor = new TaskEditor(activity, viewModel, task, () -> editor = null);
        editor.show();
    }

    private void confirmDelete(Task task) {
        TasksState state = viewModel.state().getValue();
        if (closed || confirmation != null || state == null || state.saving || state.loading) {
            return;
        }
        confirmation = new AlertDialog.Builder(activity)
                .setTitle(R.string.tasks_delete_title)
                .setMessage(activity.getString(R.string.tasks_delete_message, task.title))
                .setNegativeButton(R.string.tasks_cancel, null)
                .setPositiveButton(R.string.tasks_delete, (dialog, which) ->
                        viewModel.delete(task, success -> {
                            if (!closed && !success) {
                                toast(R.string.tasks_delete_failed);
                            }
                        }))
                .create();
        confirmation.setOnDismissListener(dialog -> confirmation = null);
        confirmation.show();
    }

    private void toast(int message) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        viewModel.state().removeObserver(observer);
        viewModel.cancelLoad();
        binding.getRoot().removeOnLayoutChangeListener(contentSizeListener);
        binding.tasksContent.setAdapter(null);
        if (editor != null) {
            editor.close();
        }
        if (confirmation != null) {
            confirmation.dismiss();
        }
    }
}
