package com.stride.android.ui;

import android.view.View;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.stride.android.adapter.EntityAdapter;
import com.stride.android.model.EntityListState;
import com.stride.android.utils.FeatureUi;
import com.stride.android.utils.SupabaseApi;
import com.stride.android.viewmodel.EntityListViewModel;

import org.json.JSONObject;

/** Shared list wiring; individual screens own their binding and editor behavior. */
abstract class EntityScreen implements FeatureScreen {
    protected final JSONObject user;
    protected final FeatureUi ui;
    protected final EntityListViewModel viewModel;
    private final EntityAdapter adapter = new EntityAdapter();
    private Observer<EntityListState> observer;
    protected boolean closed;

    EntityScreen(ComponentActivity activity, SupabaseApi api, JSONObject user, String table) {
        this.user = user;
        ui = new FeatureUi(activity);
        viewModel = new ViewModelProvider(activity).get(
                "entity:" + table + ":" + user.optString("id"), EntityListViewModel.class);
        viewModel.configure(api, table, user.optString("id"));
    }

    protected void bindList(ComponentActivity activity, RecyclerView list,
            TextView empty, String errorMessage) {
        CharSequence emptyMessage = empty.getText();
        list.setLayoutManager(new LinearLayoutManager(activity));
        list.setAdapter(adapter);
        observer = state -> {
            adapter.submitList(state.rows);
            empty.setText(state.error == null ? emptyMessage : errorMessage);
            empty.setVisibility(!state.loading && state.rows.isEmpty() ? View.VISIBLE : View.GONE);
        };
        viewModel.state().observe(activity, observer);
    }

    @Override
    public void close() {
        closed = true;
        viewModel.cancelLoad();
        if (observer != null) {
            viewModel.state().removeObserver(observer);
        }
    }
}
