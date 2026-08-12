package com.stride.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import com.stride.android.databinding.ScreenSettingsBinding;
import org.json.JSONArray;
import org.json.JSONObject;

final class SettingsScreen implements FeatureScreen {
    private final ScreenSettingsBinding binding;
    private Activity activity;
    private SupabaseApi api;
    private JSONObject user;
    private Runnable signOut;
    private FeatureUi ui;
    SettingsScreen(LayoutInflater inflater) { binding = ScreenSettingsBinding.inflate(inflater); }
    SettingsScreen(Activity activity, SupabaseApi api, JSONObject user, Runnable signOut) {
        this.binding = ScreenSettingsBinding.inflate(activity.getLayoutInflater());
        this.activity = activity; this.api = api; this.user = user; this.signOut = signOut; this.ui = new FeatureUi(activity);
    }
    public View root() { return binding.getRoot(); }
    public LinearLayout content() { return binding.contentContainer; }
    TextView email() { return binding.profileEmail; }
    Button editButton() { return binding.editButton; }
    Button featuresButton() { return binding.featuresButton; }
    Button logoutButton() { return binding.logoutButton; }
    void bind() {
        email().setText(user.optString("email"));
        editButton().setOnClickListener(v -> editProfile());
        featuresButton().setOnClickListener(v -> chooseFeatures());
        logoutButton().setOnClickListener(v -> signOut.run());
    }
    private void editProfile() {
        LinearLayout form = ui.dialogForm(); EditText name = ui.field("Full name", 1), email = ui.field("Email", 1);
        name.setText(user.optString("full_name")); email.setText(user.optString("email")); form.addView(name); form.addView(email);
        new AlertDialog.Builder(activity).setTitle("Edit profile").setView(form).setPositiveButton("Save", (d,w) -> api.update("users", SupabaseApi.eq("id", user.optString("id")), FeatureUi.obj("full_name",name.getText().toString().trim(),"email",email.getText().toString().trim()), (rows,error) -> {
            if(error != null) { ui.toast("Could not save profile"); return; }
            user = FeatureUi.obj("id",user.optString("id"),"full_name",name.getText().toString().trim(),"email",email.getText().toString().trim(),"features",user.opt("features")); email().setText(user.optString("email"));
        }).setNegativeButton("Cancel",null).show();
    }
    private void chooseFeatures() {
        String[] names={"Notes","Calendar","Todo","Goals"}; boolean[] selected=new boolean[4]; String values=user.optString("features","");
        for(int i=0;i<4;i++) selected[i]=values.isEmpty()||values.contains(names[i]);
        new AlertDialog.Builder(activity).setTitle("Dashboard features").setMultiChoiceItems(names,selected,(d,w,on)->selected[w]=on).setPositiveButton("Save",(d,w)->{ JSONArray features=new JSONArray(); for(int i=0;i<4;i++)if(selected[i])features.put(names[i]); api.update("users",SupabaseApi.eq("id",user.optString("id")),FeatureUi.obj("features",features),(rows,error)->{if(error==null){try{user.put("features",features);}catch(Exception ignored){}ui.toast("Features updated");}else ui.toast("Could not update features");}); }).setNegativeButton("Cancel",null).show();
    }
}
