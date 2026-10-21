package com.stride.android.ui;

import android.app.Activity;
import android.view.View;

import com.stride.android.databinding.ActivitySignupBinding;
import com.stride.android.utils.FeatureUi;
import com.stride.android.viewmodel.SessionViewModel;

/** Owns authentication form binding, validation, and user feedback. */
public final class SignupScreen implements FeatureScreen {
    private final ActivitySignupBinding binding;
    private final SessionViewModel session;
    private final FeatureUi ui;
    private boolean closed;

    public SignupScreen(Activity activity, SessionViewModel session) {
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
    }

    public void bind(Runnable success, Runnable switchScreen) {
        binding.backToSignInButton.setOnClickListener(v -> switchScreen.run());
        binding.createAccountButton.setOnClickListener(v -> {
            String name = binding.nameInput.getText().toString().trim();
            String email = binding.emailInput.getText().toString().trim();
            String password = binding.passwordInput.getText().toString();
            if (name.isEmpty() || email.isEmpty() || password.isEmpty()) {
                ui.toast("Complete every field");
                return;
            }
            if (!password.equals(binding.confirmPasswordInput.getText().toString())) {
                ui.toast("Passwords don't match");
                return;
            }
            binding.createAccountButton.setEnabled(false);
            session.signUp(name, email, password, error -> {
                if (closed) {
                    return;
                }
                binding.createAccountButton.setEnabled(true);
                if (error != null) {
                    ui.toast(error);
                } else {
                    ui.toast("Account created. Please sign in.");
                    success.run();
                }
            });
        });
    }
}
