package com.stride.android;

import android.view.LayoutInflater;
import android.view.View;
import android.app.Activity;
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
    void bind() { load(); addButton().setOnClickListener(v->showEditor()); binding.cancelNoteButton.setOnClickListener(v->hideEditor()); binding.saveNoteButton.setOnClickListener(v->save()); }
    private void load(){list().removeAllViews();api.get("notes",SupabaseApi.and("select=*",SupabaseApi.eq("user_id",user.optString("id")),"order=updated_at.desc"),(rows,error)->{if(error!=null){binding.emptyNotes.setText("Could not load notes.");binding.emptyNotes.setVisibility(View.VISIBLE);return;}binding.emptyNotes.setVisibility(rows.length()==0?View.VISIBLE:View.GONE);for(int i=0;i<rows.length();i++){JSONObject note=rows.optJSONObject(i);ui.row(list(),note.optString("title","Untitled Note"),FeatureUi.strip(note.optString("content")),v->{});}});}
    private void showEditor(){binding.noteEditor.setVisibility(View.VISIBLE);binding.noteTitleInput.requestFocus();}
    private void hideEditor(){binding.noteEditor.setVisibility(View.GONE);binding.noteTitleInput.setText("");binding.noteContentInput.setText("");}
    private void save(){String title=binding.noteTitleInput.getText().toString().trim();api.insert("notes",FeatureUi.obj("user_id",user.optString("id"),"title",title.isEmpty()?"Untitled Note":title,"content",binding.noteContentInput.getText().toString(),"updated_at",FeatureUi.now()),(r,e)->{if(e!=null){ui.toast("Could not save note");return;}hideEditor();load();});}
}
