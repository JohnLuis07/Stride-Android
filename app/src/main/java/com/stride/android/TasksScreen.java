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
    void bind(){load();addButton().setOnClickListener(v->binding.taskEditor.setVisibility(View.VISIBLE));binding.cancelTaskButton.setOnClickListener(v->binding.taskEditor.setVisibility(View.GONE));binding.saveTaskButton.setOnClickListener(v->save());}
    private void load(){list().removeAllViews();api.get("tasks",SupabaseApi.and("select=*",SupabaseApi.eq("user_id",user.optString("id")),"order=created_at.desc"),(rows,error)->{if(error!=null){binding.emptyTasks.setText("Could not load tasks.");binding.emptyTasks.setVisibility(View.VISIBLE);return;}binding.emptyTasks.setVisibility(rows.length()==0?View.VISIBLE:View.GONE);for(int i=0;i<rows.length();i++){JSONObject task=rows.optJSONObject(i);ui.row(list(),task.optString("title"),task.optString("status","todo"),v->{});}});}
    private void save(){String title=binding.taskTitleInput.getText().toString().trim();if(title.isEmpty()){ui.toast("Enter a task title");return;}api.insert("tasks",FeatureUi.obj("user_id",user.optString("id"),"title",title,"details",FeatureUi.emptyNull(binding.taskDetailsInput),"due_date",FeatureUi.emptyNull(binding.taskDueInput),"priority",binding.taskPriorityInput.getText().toString().trim(),"status","todo","updated_at",FeatureUi.now()),(r,e)->{if(e!=null){ui.toast("Could not save task");return;}binding.taskEditor.setVisibility(View.GONE);binding.taskTitleInput.setText("");binding.taskDetailsInput.setText("");load();});}
}
