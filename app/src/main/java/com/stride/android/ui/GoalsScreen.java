package com.stride.android.ui;

import android.app.AlertDialog;
import android.view.View;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.stride.android.R;
import com.stride.android.adapter.GoalAdapter;
import com.stride.android.databinding.ScreenGoalsBinding;
import com.stride.android.model.Goal;
import com.stride.android.model.GoalsState;
import com.stride.android.utils.CalendarDates;
import com.stride.android.utils.SupabaseApi;
import com.stride.android.viewmodel.GoalsViewModel;

import org.json.JSONObject;

/** Goal workspace with a single scrolling overview and binding-backed cards. */
public final class GoalsScreen implements FeatureScreen {
    private final ComponentActivity activity;
    private final ScreenGoalsBinding binding;
    private final LoadingView loadingView;
    private final GoalsViewModel viewModel;
    private final Observer<GoalsState> observer = this::render;
    private GoalAdapter adapter;
    private final RecyclerView.AdapterDataObserver dataObserver = new RecyclerView.AdapterDataObserver() {
        @Override
        public void onChanged() {
            revealLoadedGoals();
        }

        @Override
        public void onItemRangeInserted(int positionStart, int itemCount) {
            revealLoadedGoals();
        }

        @Override
        public void onItemRangeRemoved(int positionStart, int itemCount) {
            revealLoadedGoals();
        }
    };
    private GoalEditor editor;
    private AlertDialog confirmation;
    private CalendarDates.DateObserver dateObserver;
    private boolean closed;

    public GoalsScreen(ComponentActivity activity, SupabaseApi api, JSONObject user) {
        this.activity = activity;
        binding = ScreenGoalsBinding.inflate(activity.getLayoutInflater());
        viewModel = new ViewModelProvider(activity).get(
                "goals:" + user.optString("id"), GoalsViewModel.class);
        viewModel.configure(api, user.optString("id"));
        loadingView = new LoadingView(activity, binding.getRoot(), viewModel::reload);
    }

    @Override
    public View root() {
        return loadingView.root();
    }

    public void bind() {
        adapter = new GoalAdapter(new GoalAdapter.Actions() {
            @Override
            public void onCreate() {
                showEditor(null, false);
            }

            @Override
            public void onRefresh() {
                viewModel.reload();
            }

            @Override
            public void onFilter(String filter) {
                viewModel.setFilter(filter);
            }

            @Override
            public void onEdit(Goal goal) {
                showEditor(goal, false);
            }

            @Override
            public void onDelete(Goal goal) {
                confirmDelete(goal);
            }

            @Override
            public void onProgress(Goal goal) {
                showEditor(goal, true);
            }

            @Override
            public void onStep(Goal goal, boolean increase) {
                if (canAct()) {
                    viewModel.stepProgress(goal, increase, success -> {
                        if (!closed && !success) {
                            toast(R.string.goals_progress_failed);
                        }
                    });
                }
            }
        });
        binding.goalsContent.setLayoutManager(new LinearLayoutManager(activity));
        binding.goalsContent.setItemAnimator(null);
        binding.goalsContent.setAdapter(adapter);
        adapter.registerAdapterDataObserver(dataObserver);
        viewModel.state().observe(activity, observer);
        dateObserver = new CalendarDates.DateObserver(activity, activity, () -> {
            if (!closed) {
                adapter.notifyItemRangeChanged(0, adapter.getItemCount());
            }
        });
        loadingView.update(true, false);
        viewModel.reload();
    }

    private void render(GoalsState state) {
        if (!closed) {
            adapter.submitState(state);
            revealLoadedGoals();
        }
    }

    private void revealLoadedGoals() {
        GoalsState state = viewModel.state().getValue();
        if (!closed && state != null && (state.loading || state.loadFailed
                || adapter.getItemCount() == state.visibleGoals.size() + 1)) {
            loadingView.update(state.loading, state.loadFailed);
        }
    }

    private boolean canAct() {
        GoalsState state = viewModel.state().getValue();
        return !closed && editor == null && confirmation == null && state != null
                && !state.loading && !state.saving && !state.loadFailed;
    }

    private void showEditor(Goal goal, boolean progressOnly) {
        if (!canAct()) {
            return;
        }
        editor = new GoalEditor(activity, viewModel, goal, progressOnly, () -> editor = null);
        editor.show();
    }

    private void confirmDelete(Goal goal) {
        if (!canAct()) {
            return;
        }
        confirmation = new AlertDialog.Builder(activity)
                .setTitle(R.string.goals_delete)
                .setMessage(activity.getString(R.string.goals_delete_message, goal.title))
                .setNegativeButton(R.string.goals_cancel, null)
                .setPositiveButton(R.string.goals_delete, (dialog, which) ->
                        viewModel.delete(goal, success -> {
                            if (!closed && !success) {
                                toast(R.string.goals_delete_failed);
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
        if (adapter != null) {
            adapter.unregisterAdapterDataObserver(dataObserver);
        }
        if (dateObserver != null) {
            dateObserver.close();
        }
        viewModel.state().removeObserver(observer);
        viewModel.cancelLoad();
        binding.goalsContent.setAdapter(null);
        if (editor != null) {
            editor.close();
        }
        if (confirmation != null) {
            confirmation.dismiss();
        }
    }
}
