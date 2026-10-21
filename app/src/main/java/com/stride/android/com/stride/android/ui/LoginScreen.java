package com.stride.android.ui;

import android.app.Activity;
import android.view.View;

import com.stride.android.databinding.ActivityLoginBinding;
import com.stride.android.utils.FeatureUi;
import com.stride.android.viewmodel.SessionViewModel;

/** Owns authentication form binding, validation, and user feedback. */
public final class LoginScreen implements FeatureScreen {
    private final ActivityLoginBinding binding;
    private final SessionViewModel session;
    private final FeatureUi ui;
    private boolean closed;

    public LoginScreen(Activity activity, SessionViewModel session) {
        binding = ActivityLoginBinding.inflate(activity.getLayoutInflater());
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
        binding.createAccountButton.setOnClickListener(v -> switchScreen.run());
        binding.signInButton.setOnClickListener(v -> {
            String email = binding.emailInput.getText().toString().trim();
            String password = binding.passwordInput.getText().toString();
            if (email.isEmpty() || password.isEmpty()) {
                ui.toast("Enter your email and password");
                return;
            }
            binding.signInButton.setEnabled(false);
            session.signIn(email, password, error -> {
                if (closed) {
                    return;
                }
                binding.signInButton.setEnabled(true);
                if (error == null) {
                    success.run();
                } else {
                    ui.toast(error);
                }
            });
        });
    }
}
