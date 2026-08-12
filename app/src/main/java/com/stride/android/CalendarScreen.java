package com.stride.android;

import android.view.LayoutInflater;
import android.view.View;
import android.app.Activity;
import android.widget.LinearLayout;
import android.widget.Button;
import android.widget.EditText;
import com.stride.android.databinding.ScreenCalendarBinding;
import org.json.JSONObject;

final class CalendarScreen implements FeatureScreen {
    private final ScreenCalendarBinding binding;
    private Activity activity;private SupabaseApi api;private JSONObject user;private FeatureUi ui;
    CalendarScreen(LayoutInflater inflater) { binding = ScreenCalendarBinding.inflate(inflater); }
    CalendarScreen(Activity activity,SupabaseApi api,JSONObject user){binding=ScreenCalendarBinding.inflate(activity.getLayoutInflater());this.activity=activity;this.api=api;this.user=user;this.ui=new FeatureUi(activity);}
    public View root() { return binding.getRoot(); }
    public LinearLayout content() { return binding.contentContainer; }
    EditText date() { return binding.dateInput; }
    Button addButton() { return binding.addButton; }
    LinearLayout list() { return binding.listContainer; }
    void bind(){date().setText(FeatureUi.today());load();date().setOnFocusChangeListener((v,focused)->{if(!focused)load();});addButton().setOnClickListener(v->binding.eventEditor.setVisibility(View.VISIBLE));binding.cancelEventButton.setOnClickListener(v->binding.eventEditor.setVisibility(View.GONE);binding.saveEventButton.setOnClickListener(v->save());}
    private void load(){list().removeAllViews();api.get("calendar_events",SupabaseApi.and("select=*",SupabaseApi.eq("user_id",user.optString("id")),SupabaseApi.eq("event_date",date().getText().toString()),"order=start_time.asc"),(rows,error)->{binding.emptyEvents.setVisibility(rows.length()==0?View.VISIBLE:View.GONE);for(int i=0;i<rows.length();i++){JSONObject event=rows.optJSONObject(i);ui.row(list(),event.optString("title"),event.optString("category","Focus")+" · "+event.optString("start_time","Any time"),v->{});}});}
    private void save(){String title=binding.eventTitleInput.getText().toString().trim();if(title.isEmpty()){ui.toast("Enter an event title");return;}api.insert("calendar_events",FeatureUi.obj("user_id",user.optString("id"),"title",title,"details",FeatureUi.emptyNull(binding.eventDetailsInput),"event_date",date().getText().toString().trim(),"start_time",FeatureUi.emptyNull(binding.eventStartInput),"end_time",FeatureUi.emptyNull(binding.eventEndInput),"category",binding.eventCategoryInput.getText().toString().trim(),"updated_at",FeatureUi.now()),(r,e)->{if(e!=null){ui.toast("Could not save event");return;}binding.eventEditor.setVisibility(View.GONE);load();});}
}
