package com.stride.android;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Button;
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
        editButton().setOnClickListener(v -> showProfileEditor());
        binding.cancelProfileButton.setOnClickListener(v -> hideProfileEditor());
        binding.saveProfileButton.setOnClickListener(v -> saveProfile());
        featuresButton().setOnClickListener(v -> showFeaturesEditor());
        binding.cancelFeaturesButton.setOnClickListener(v -> hideFeaturesEditor());
        binding.saveFeaturesButton.setOnClickListener(v -> saveFeatures());
        logoutButton().setOnClickListener(v -> signOut.run());
    }
    private void showProfileEditor() {
        binding.profileNameInput.setText(user.optString("full_name"));
        binding.profileEmailInput.setText(user.optString("email"));
        binding.profileEditor.setVisibility(View.VISIBLE);
    }
    private void hideProfileEditor() {
        binding.profileEditor.setVisibility(View.GONE);
    }
    private void saveProfile() {
        String fullName = binding.profileNameInput.getText().toString().trim();
        String userEmail = binding.profileEmailInput.getText().toString().trim();
        if (userEmail.isEmpty()) {
            ui.toast("Email is required");
            return;
        }
        api.update("users", SupabaseApi.eq("id", user.optString("id")), FeatureUi.obj("full_name", fullName, "email", userEmail), (rows, error) -> {
            if(error != null) { ui.toast("Could not save profile"); return; }
            user = FeatureUi.obj("id", user.optString("id"), "full_name", fullName, "email", userEmail, "features", user.opt("features"));
            email().setText(user.optString("email"));
            hideProfileEditor();
        });
    }
    private void showFeaturesEditor() {
        String values = user.optString("features", "");
        binding.featureNotesCheckbox.setChecked(values.isEmpty() || values.contains("Notes"));
        binding.featureCalendarCheckbox.setChecked(values.isEmpty() || values.contains("Calendar"));
        binding.featureTasksCheckbox.setChecked(values.isEmpty() || values.contains("Todo"));
        binding.featureGoalsCheckbox.setChecked(values.isEmpty() || values.contains("Goals"));
        binding.featuresEditor.setVisibility(View.VISIBLE);
    }
    private void hideFeaturesEditor() {
        binding.featuresEditor.setVisibility(View.GONE);
    }
    private void saveFeatures() {
        JSONArray features = new JSONArray();
        if (binding.featureNotesCheckbox.isChecked()) features.put("Notes");
        if (binding.featureCalendarCheckbox.isChecked()) features.put("Calendar");
        if (binding.featureTasksCheckbox.isChecked()) features.put("Todo");
        if (binding.featureGoalsCheckbox.isChecked()) features.put("Goals");
        api.update("users", SupabaseApi.eq("id", user.optString("id")), FeatureUi.obj("features", features), (rows, error) -> {
            if(error==null){
                try { user.put("features",features); } catch(Exception ignored) {}
                ui.toast("Features updated");
                hideFeaturesEditor();
            } else {
                ui.toast("Could not update features");
            }
        });
    }
}
