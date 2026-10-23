package com.stride.android.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.util.Patterns;
import android.view.View;
import android.view.inputmethod.InputMethodManager;

import androidx.recyclerview.widget.LinearLayoutManager;

import com.stride.android.R;
import com.stride.android.adapter.SettingsFeatureAdapter;
import com.stride.android.databinding.ScreenSettingsBinding;
import com.stride.android.utils.FeatureUi;
import com.stride.android.viewmodel.SessionViewModel;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

public final class SettingsScreen implements FeatureScreen {
    private final Activity activity;
    private final ScreenSettingsBinding binding;
    private final SessionViewModel session;
    private final JSONObject user;
    private final Runnable signOut;
    private final FeatureUi ui;
    private final SettingsFeatureAdapter featureAdapter;
    private boolean closed;
    private boolean savingProfile;
    private boolean savingFeatures;
    private AlertDialog signOutDialog;

    public SettingsScreen(Activity activity, SessionViewModel session, Runnable signOut) {
        this.activity = activity;
        binding = ScreenSettingsBinding.inflate(activity.getLayoutInflater());
        this.session = session;
        user = session.user();
        this.signOut = signOut;
        ui = new FeatureUi(activity);
        featureAdapter = new SettingsFeatureAdapter(this::updateFeatureSummary);
        binding.featuresList.setLayoutManager(new LinearLayoutManager(activity));
        binding.featuresList.setAdapter(featureAdapter);
        binding.featuresList.setItemAnimator(null);
    }

    @Override
    public View root() {
        return binding.getRoot();
    }

    public void bind() {
        renderProfile();
        restoreFeatures();
        binding.editButton.setOnClickListener(view -> showProfileEditor());
        binding.cancelProfileButton.setOnClickListener(view -> hideProfileEditor());
        binding.saveProfileButton.setOnClickListener(view -> saveProfile());
        binding.featuresButton.setOnClickListener(view -> showFeaturesEditor());
        binding.cancelFeaturesButton.setOnClickListener(view -> {
            restoreFeatures();
            hideFeaturesEditor();
        });
        binding.saveFeaturesButton.setOnClickListener(view -> saveFeatures());
        binding.logoutButton.setOnClickListener(view -> confirmSignOut());
    }

    private void renderProfile() {
        String name = user.optString("full_name", "").trim();
        binding.profileName.setText(name.isEmpty() ? activity.getString(R.string.settings_member) : name);
        binding.profileEmail.setText(user.optString("email", ""));
        String source = name.isEmpty() ? user.optString("email", "") : name;
        String[] parts = source.trim().split("\\s+");
        String initials = source.isEmpty() ? "S" : firstLetter(parts[0]);
        if (parts.length > 1) {
            initials += firstLetter(parts[parts.length - 1]);
        }
        binding.profileInitials.setText(initials.toUpperCase(Locale.ROOT));
    }

    private String firstLetter(String value) {
        return value.isEmpty() ? "" : value.substring(0, value.offsetByCodePoints(0, 1));
    }

    private void showProfileEditor() {
        if (savingProfile || savingFeatures) {
            return;
        }
        hideFeaturesEditor();
        restoreFeatures();
        binding.profileNameInput.setText(user.optString("full_name", ""));
        binding.profileEmailInput.setText(user.optString("email", ""));
        binding.profileNameInput.setError(null);
        binding.profileEmailInput.setError(null);
        binding.profileEditor.setVisibility(View.VISIBLE);
        binding.editButton.setVisibility(View.GONE);
    }

    private void hideProfileEditor() {
        hideKeyboard();
        binding.profileEditor.setVisibility(View.GONE);
        binding.editButton.setVisibility(View.VISIBLE);
    }

    private void saveProfile() {
        if (savingProfile) {
            return;
        }
        String name = binding.profileNameInput.getText().toString().trim();
        String email = binding.profileEmailInput.getText().toString().trim();
        binding.profileNameInput.setError(null);
        binding.profileEmailInput.setError(null);
        if (name.isEmpty()) {
            binding.profileNameInput.setError(activity.getString(R.string.settings_name_required));
            binding.profileNameInput.requestFocus();
            return;
        }
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.profileEmailInput.setError(activity.getString(R.string.settings_email_invalid));
            binding.profileEmailInput.requestFocus();
            return;
        }
        hideKeyboard();
        savingProfile = true;
        updateSavingState();
        session.saveProfile(name, email, error -> {
            if (closed) {
                return;
            }
            savingProfile = false;
            updateSavingState();
            if (error != null) {
                ui.toast(activity.getString(R.string.settings_profile_error));
                return;
            }
            renderProfile();
            hideProfileEditor();
            ui.toast(activity.getString(R.string.settings_profile_saved));
        });
    }

    private void restoreFeatures() {
        JSONArray values = user.optJSONArray("features");
        if (values == null) {
            String raw = user.optString("features", "").trim();
            if (!raw.isEmpty() && !"null".equals(raw)) {
                try {
                    values = new JSONArray(raw);
                } catch (org.json.JSONException ignored) {
                    // Preserve the existing default when no valid preference array is stored.
                }
            }
        }
        featureAdapter.setSelection(values);
        updateFeatureSummary();
    }

    private void updateFeatureSummary() {
        binding.featureSummary.setText(activity.getString(R.string.settings_feature_count,
                featureAdapter.selection().length(), featureAdapter.getItemCount()));
    }

    private void showFeaturesEditor() {
        if (savingProfile || savingFeatures) {
            return;
        }
        hideProfileEditor();
        restoreFeatures();
        binding.featuresEditor.setVisibility(View.VISIBLE);
        binding.featuresButton.setVisibility(View.GONE);
    }

    private void hideFeaturesEditor() {
        binding.featuresEditor.setVisibility(View.GONE);
        binding.featuresButton.setVisibility(View.VISIBLE);
    }

    private void saveFeatures() {
        if (savingFeatures) {
            return;
        }
        JSONArray features = featureAdapter.selection();
        savingFeatures = true;
        updateSavingState();
        session.saveFeatures(features, error -> {
            if (closed) {
                return;
            }
            savingFeatures = false;
            updateSavingState();
            if (error != null) {
                ui.toast(activity.getString(R.string.settings_features_error));
                return;
            }
            restoreFeatures();
            hideFeaturesEditor();
            ui.toast(activity.getString(R.string.settings_features_saved));
        });
    }

    private void updateSavingState() {
        boolean idle = !savingProfile && !savingFeatures;
        binding.editButton.setEnabled(idle);
        binding.featuresButton.setEnabled(idle);
        binding.logoutButton.setEnabled(idle);
        binding.profileNameInput.setEnabled(!savingProfile);
        binding.profileEmailInput.setEnabled(!savingProfile);
        binding.saveProfileButton.setEnabled(!savingProfile);
        binding.cancelProfileButton.setEnabled(!savingProfile);
        binding.saveFeaturesButton.setEnabled(!savingFeatures);
        binding.cancelFeaturesButton.setEnabled(!savingFeatures);
        binding.saveProfileButton.setText(savingProfile ? R.string.settings_saving : R.string.settings_save_profile);
        binding.saveFeaturesButton.setText(savingFeatures ? R.string.settings_saving : R.string.settings_save_features);
        binding.profileProgress.setVisibility(savingProfile ? View.VISIBLE : View.GONE);
        binding.featuresProgress.setVisibility(savingFeatures ? View.VISIBLE : View.GONE);
        featureAdapter.setEnabled(!savingFeatures);
    }

    private void confirmSignOut() {
        if (savingProfile || savingFeatures || (signOutDialog != null && signOutDialog.isShowing())) {
            return;
        }
        signOutDialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.settings_sign_out_title)
                .setMessage(R.string.settings_sign_out_message)
                .setNegativeButton(R.string.settings_cancel, null)
                .setPositiveButton(R.string.settings_sign_out, (dialog, which) -> {
                    if (!closed) {
                        signOut.run();
                    }
                })
                .show();
    }

    private void hideKeyboard() {
        InputMethodManager input = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (input != null) {
            input.hideSoftInputFromWindow(binding.getRoot().getWindowToken(), 0);
        }
        binding.profileNameInput.clearFocus();
        binding.profileEmailInput.clearFocus();
    }

    @Override
    public void close() {
        closed = true;
        if (signOutDialog != null) {
            signOutDialog.dismiss();
            signOutDialog = null;
        }
        binding.featuresList.setAdapter(null);
    }
}
