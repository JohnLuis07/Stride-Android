package com.stride.android;

import android.view.LayoutInflater;
import android.view.View;
import android.app.Activity;
import android.app.AlertDialog;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Button;
import com.stride.android.databinding.ScreenNotesBinding;
import org.json.JSONObject;

final class NotesScreen implements FeatureScreen {
    private final ScreenNotesBinding binding;
    private Activity activity; private SupabaseApi api; private JSONObject user; private FeatureUi ui;
    NotesScreen(LayoutInflater inflater) { binding = ScreenNotesBinding.inflate(inflater); }
    NotesScreen(Activity activity, SupabaseApi api, JSONObject user) { binding=ScreenNotesBinding.inflate(activity.getLayoutInflater());this.activity=activity;this.api=api;this.user=user;this.ui=new FeatureUi(activity); }
    public View root() { return binding.getRoot(); }
    public LinearLayout content() { return binding.contentContainer; }
    Button addButton() { return binding.addButton; }
    LinearLayout list() { return binding.listContainer; }
    void bind() { load(); addButton().setOnClickListener(v->edit()); }
    private void load(){list().removeAllViews();api.get("notes",SupabaseApi.and("select=*",SupabaseApi.eq("user_id",user.optString("id")),"order=updated_at.desc"),(rows,error)->{if(error!=null){list().addView(ui.text("Could not load notes.",16,FeatureUi.MUTED));return;}if(rows.length()==0)list().addView(ui.text("No notes yet.",16,FeatureUi.MUTED));for(int i=0;i<rows.length();i++){JSONObject note=rows.optJSONObject(i);ui.row(list(),note.optString("title","Untitled Note"),FeatureUi.strip(note.optString("content")),v->edit());}});}
    private void edit(){LinearLayout form=ui.dialogForm();EditText title=ui.field("Title",1),body=ui.field("Write something worth remembering…",1);body.setMinLines(7);form.addView(title);form.addView(body);new AlertDialog.Builder(activity).setTitle("New note").setView(form).setNegativeButton("Cancel",null).setPositiveButton("Save",(d,w)->api.insert("notes",FeatureUi.obj("user_id",user.optString("id"),"title",title.getText().toString().trim().isEmpty()?"Untitled Note":title.getText().toString().trim(),"content",body.getText().toString(),"updated_at",FeatureUi.now()),(r,e)->load())).show();}
}
