package com.stride.android;

import android.view.LayoutInflater;
import android.view.View;
import android.app.Activity;
import android.widget.LinearLayout;
import android.widget.Button;
import com.stride.android.databinding.ScreenTasksBinding;
import org.json.JSONObject;

final class TasksScreen implements FeatureScreen {
    private final ScreenTasksBinding binding;
    private Activity activity; private SupabaseApi api; private JSONObject user; private FeatureUi ui;
    TasksScreen(LayoutInflater inflater) { binding = ScreenTasksBinding.inflate(inflater); }
    TasksScreen(Activity activity,SupabaseApi api,JSONObject user){binding=ScreenTasksBinding.inflate(activity.getLayoutInflater());this.activity=activity;this.api=api;this.user=user;this.ui=new FeatureUi(activity);}
    public View root() { return binding.getRoot(); }
    public LinearLayout content() { return binding.contentContainer; }
    Button addButton() { return binding.addButton; }
    LinearLayout list() { return binding.listContainer; }
    void bind(){load();addButton().setOnClickListener(v->ui.toast("Use the task editor to add a task."));}
    private void load(){list().removeAllViews();api.get("tasks",SupabaseApi.and("select=*",SupabaseApi.eq("user_id",user.optString("id")),"order=created_at.desc"),(rows,error)->{if(error!=null){list().addView(ui.text("Could not load tasks.",16,FeatureUi.MUTED));return;}if(rows.length()==0)list().addView(ui.text("No tasks yet.",16,FeatureUi.MUTED));for(int i=0;i<rows.length();i++){JSONObject task=rows.optJSONObject(i);ui.row(list(),task.optString("title"),task.optString("status","todo"),v->{});}});}
}
