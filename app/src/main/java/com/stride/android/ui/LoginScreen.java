package com.stride.android.ui;

import android.app.Activity;
import android.util.Patterns;
import android.view.View;

import com.stride.android.R;
import com.stride.android.databinding.ActivityLoginBinding;
import com.stride.android.utils.FeatureUi;
import com.stride.android.utils.GoogleSignIn;
import com.stride.android.viewmodel.SessionViewModel;

/** Owns authentication form binding, validation, and user feedback. */
public final class LoginScreen implements FeatureScreen {
    private final ActivityLoginBinding binding;
    private final SessionViewModel session;
    private final FeatureUi ui;
    private final Activity activity;
    private final GoogleSignIn google;
    private boolean closed;
    private boolean busy;
    private boolean googlePending;
    private AuthVerificationDialog verification;

    public LoginScreen(Activity activity, SessionViewModel session) {
        this.activity = activity;
        binding = ActivityLoginBinding.inflate(activity.getLayoutInflater());
        this.session = session;
        ui = new FeatureUi(activity);
        google = new GoogleSignIn(activity);
    }

    @Override
    public View root() {
        return binding.getRoot();
    }

    @Override
    public void close() {
        closed = true;
        google.cancel();
        if (verification != null) verification.close();
        if (googlePending) {
            session.cancelGoogleSignIn();
        } else if (busy && verification == null) {
            session.cancelVerification();
        }
    }

    public void bind(Runnable success, Runnable switchScreen) {
        binding.createAccountButton.setOnClickListener(v -> switchScreen.run());
        binding.forgotPasswordButton.setOnClickListener(v -> openVerification(
                binding.emailInput.getText().toString().trim(), "recovery", success));
        binding.googleSignInButton.setOnClickListener(v -> {
            if (busy) {
                return;
            }
            setBusy(true, true);
            google.signIn((token, nonce, errorResource) -> {
                if (closed) {
                    return;
                }
                if (token == null) {
                    setBusy(false, false);
                    if (errorResource != 0) {
                        ui.toast(activity.getString(errorResource));
                    }
                    return;
                }
                googlePending = true;
                session.signInWithGoogle(token, nonce, error -> {
                    if (closed) {
                        return;
                    }
                    googlePending = false;
                    setBusy(false, false);
                    if (error == null) {
                        success.run();
                    } else {
                        ui.toast(error);
                    }
                });
            });
        });
        binding.signInButton.setOnClickListener(v -> {
            if (busy) {
                return;
            }
            String email = binding.emailInput.getText().toString().trim();
            String password = binding.passwordInput.getText().toString();
            if (!Patterns.EMAIL_ADDRESS.matcher(email).matches() || password.isEmpty()) {
                ui.toast(activity.getString(R.string.auth_complete_fields));
                return;
            }
            setBusy(true, false);
            session.signIn(email, password, error -> {
                if (closed) {
                    return;
                }
                setBusy(false, false);
                if (error == null) {
                    success.run();
                } else if (SessionViewModel.EMAIL_CONFIRMATION_REQUIRED.equals(error)) {
                    openVerification(email, "signup", success);
                } else {
                    ui.toast(error);
                }
            });
        });
    }

    private void setBusy(boolean value, boolean googleRequest) {
        busy = value;
        binding.signInButton.setEnabled(!value);
        binding.googleSignInButton.setEnabled(!value);
        binding.createAccountButton.setEnabled(!value);
        binding.forgotPasswordButton.setEnabled(!value);
        binding.emailInput.setEnabled(!value);
        binding.passwordInput.setEnabled(!value);
        binding.googleSignInButton.setText(value && googleRequest
                ? R.string.google_signing_in : R.string.google_continue);
    }

    private void openVerification(String email, String type, Runnable success) {
        if (closed || verification != null || busy) return;
        setBusy(true, false);
        verification = new AuthVerificationDialog(activity, session, email, type, success, () -> {
            verification = null;
            if (!closed) setBusy(false, false);
        });
        verification.show();
    }
}
