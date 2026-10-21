package com.stride.android.ui;

import android.app.Activity;
import android.view.View;

import com.stride.android.databinding.ScreenSettingsBinding;
import com.stride.android.utils.FeatureUi;
import com.stride.android.viewmodel.SessionViewModel;

import org.json.JSONArray;
import org.json.JSONObject;

public final class SettingsScreen implements FeatureScreen {

    private final ScreenSettingsBinding binding;
    private boolean closed;
    private final SessionViewModel session;
    private final JSONObject user;
    private final Runnable signOut;
    private final FeatureUi ui;

    public SettingsScreen(
            Activity activity,
            SessionViewModel session,
            Runnable signOut
    ) {
        this.binding = ScreenSettingsBinding.inflate(
                activity.getLayoutInflater()
        );
        this.session = session;
        this.user = session.user();
        this.signOut = signOut;
        this.ui = new FeatureUi(activity);
    }

    @Override
    public View root() {
        return binding.getRoot();
    }

    public void bind() {
        binding.profileEmail.setText(user.optString("email"));

        binding.editButton.setOnClickListener(
                v -> showProfileEditor()
        );

        binding.cancelProfileButton.setOnClickListener(
                v -> hideProfileEditor()
        );

        binding.saveProfileButton.setOnClickListener(
                v -> saveProfile()
        );

        binding.featuresButton.setOnClickListener(
                v -> showFeaturesEditor()
        );

        binding.cancelFeaturesButton.setOnClickListener(
                v -> hideFeaturesEditor()
        );

        binding.saveFeaturesButton.setOnClickListener(
                v -> saveFeatures()
        );

        binding.logoutButton.setOnClickListener(
                v -> signOut.run()
        );
    }

    private void showProfileEditor() {
        binding.profileNameInput.setText(
                user.optString("full_name")
        );

        binding.profileEmailInput.setText(
                user.optString("email")
        );

        binding.profileEditor.setVisibility(View.VISIBLE);
    }

    private void hideProfileEditor() {
        binding.profileEditor.setVisibility(View.GONE);
    }

    private void saveProfile() {
        String fullName = binding.profileNameInput
                .getText()
                .toString()
                .trim();

        String userEmail = binding.profileEmailInput
                .getText()
                .toString()
                .trim();

        if (userEmail.isEmpty()) {
            ui.toast("Email is required");
            return;
        }

        session.saveProfile(fullName, userEmail, error -> {
            if (closed) {
                return;
            }
            if (error != null) {
                ui.toast("Could not save profile");
                return;
            }
            binding.profileEmail.setText(user.optString("email"));
            hideProfileEditor();
        });
    }

    private void showFeaturesEditor() {
        String values = user.optString("features", "");

        binding.featureNotesCheckbox.setChecked(
                values.isEmpty() || values.contains("Notes")
        );

        binding.featureCalendarCheckbox.setChecked(
                values.isEmpty() || values.contains("Calendar")
        );

        binding.featureTasksCheckbox.setChecked(
                values.isEmpty() || values.contains("Todo")
        );

        binding.featureGoalsCheckbox.setChecked(
                values.isEmpty() || values.contains("Goals")
        );

        binding.ftFocusCheckbox.setChecked(
                values.isEmpty() || values.contains("Focus")
        );

        binding.featuresEditor.setVisibility(View.VISIBLE);
    }

    private void hideFeaturesEditor() {
        binding.featuresEditor.setVisibility(View.GONE);
    }

    private void saveFeatures() {
        JSONArray features = new JSONArray();

        if (binding.featureNotesCheckbox.isChecked()) {
            features.put("Notes");
        }

        if (binding.featureCalendarCheckbox.isChecked()) {
            features.put("Calendar");
        }

        if (binding.featureTasksCheckbox.isChecked()) {
            features.put("Todo");
        }

        if (binding.featureGoalsCheckbox.isChecked()) {
            features.put("Goals");
        }

        if (binding.featureGoalsCheckbox.isChecked()) {
            features.put("Focus");
        }

        session.saveFeatures(features, error -> {
            if (closed) {
                return;
            }
            if (error == null) {
                ui.toast("Features updated");
                hideFeaturesEditor();
            } else {
                ui.toast("Could not update features");
            }
        });
    }

    @Override
    public void close() {
        closed = true;
    }
}
