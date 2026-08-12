package com.stride.android;

import android.view.LayoutInflater;
import android.view.View;
import android.app.Activity;
import android.widget.LinearLayout;
import android.widget.Button;
import com.stride.android.databinding.ScreenGoalsBinding;
import org.json.JSONObject;

final class GoalsScreen implements FeatureScreen {
    private final ScreenGoalsBinding binding;
    private Activity activity;private SupabaseApi api;private JSONObject user;private FeatureUi ui;
    GoalsScreen(LayoutInflater inflater) { binding = ScreenGoalsBinding.inflate(inflater); }
    GoalsScreen(Activity activity,SupabaseApi api,JSONObject user){binding=ScreenGoalsBinding.inflate(activity.getLayoutInflater());this.activity=activity;this.api=api;this.user=user;this.ui=new FeatureUi(activity);}
    public View root() { return binding.getRoot(); }
    public LinearLayout content() { return binding.contentContainer; }
    Button addButton() { return binding.addButton; }
    LinearLayout list() { return binding.listContainer; }
    void bind(){load();addButton().setOnClickListener(v->binding.goalEditor.setVisibility(View.VISIBLE));binding.cancelGoalButton.setOnClickListener(v->binding.goalEditor.setVisibility(View.GONE));binding.saveGoalButton.setOnClickListener(v->save());}
    private void load(){list().removeAllViews();api.get("goals",SupabaseApi.and("select=*",SupabaseApi.eq("user_id",user.optString("id")),"order=created_at.desc"),(rows,error)->{binding.emptyGoals.setVisibility(rows.length()==0?View.VISIBLE:View.GONE);for(int i=0;i<rows.length();i++){JSONObject goal=rows.optJSONObject(i);ui.row(list(),goal.optString("title"),goal.optString("current_value","0")+" / "+goal.optString("target_value","100")+" "+goal.optString("unit","%"),v->{});}});}
    private void save(){String title=binding.goalTitleInput.getText().toString().trim();if(title.isEmpty()){ui.toast("Enter a goal title");return;}api.insert("goals",FeatureUi.obj("user_id",user.optString("id"),"title",title,"target_value",FeatureUi.number(binding.goalTargetInput,100),"current_value",0,"unit",binding.goalUnitInput.getText().toString().trim(),"updated_at",FeatureUi.now()),(r,e)->{if(e!=null){ui.toast("Could not save goal");return;}binding.goalEditor.setVisibility(View.GONE);load();});}
}
