package com.stride.android.ui;

import android.app.Activity;
import android.util.Patterns;
import android.view.View;

import com.stride.android.R;
import com.stride.android.databinding.ActivitySignupBinding;
import com.stride.android.utils.FeatureUi;
import com.stride.android.viewmodel.SessionViewModel;

/** Owns authentication form binding, validation, and user feedback. */
public final class SignupScreen implements FeatureScreen {
    private final ActivitySignupBinding binding;
    private final SessionViewModel session;
    private final FeatureUi ui;
    private boolean closed;
    private boolean busy;
    private final Activity activity;
    private AuthVerificationDialog verification;

    public SignupScreen(Activity activity, SessionViewModel session) {
        this.activity = activity;
        binding = ActivitySignupBinding.inflate(activity.getLayoutInflater());
        this.session = session;
        ui = new FeatureUi(activity);
    }

    @Override
    public View root() {
        return binding.getRoot();
    }

    @Override
    public void close() {
        closed = true;
        if (verification != null) verification.close();
        else if (busy) session.cancelVerification();
    }

    public void bind(Runnable success, Runnable switchScreen) {
        binding.backToSignInButton.setOnClickListener(v -> switchScreen.run());
        binding.createAccountButton.setOnClickListener(v -> {
            if (busy) return;
            String name = binding.nameInput.getText().toString().trim();
            String email = binding.emailInput.getText().toString().trim();
            String password = binding.passwordInput.getText().toString();
            if (name.isEmpty() || email.isEmpty() || password.isEmpty()) {
                ui.toast(activity.getString(R.string.auth_complete_fields));
                return;
            }
            if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                ui.toast(activity.getString(R.string.auth_email_invalid)); return;
            }
            if (password.length() < 8) { ui.toast(activity.getString(R.string.auth_password_short)); return; }
            if (!password.equals(binding.confirmPasswordInput.getText().toString())) {
                ui.toast(activity.getString(R.string.auth_password_mismatch));
                return;
            }
            setBusy(true);
            session.signUp(name, email, password, error -> {
                if (closed) {
                    return;
                }
                setBusy(false);
                if (SessionViewModel.EMAIL_CONFIRMATION_REQUIRED.equals(error)) {
                    setBusy(true);
                    verification = new AuthVerificationDialog(activity, session, email, "signup", success, () -> {
                        verification = null;
                        if (!closed) setBusy(false);
                    });
                    verification.show();
                } else if (error != null) {
                    ui.toast(error);
                } else {
                    success.run();
                }
            });
        });
    }

    private void setBusy(boolean value) {
        busy = value;
        binding.createAccountButton.setEnabled(!value);
        binding.backToSignInButton.setEnabled(!value);
        binding.nameInput.setEnabled(!value);
        binding.emailInput.setEnabled(!value);
        binding.passwordInput.setEnabled(!value);
        binding.confirmPasswordInput.setEnabled(!value);
    }
}
