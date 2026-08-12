package com.stride.android;

import android.view.LayoutInflater;
import android.widget.Button;
import android.widget.EditText;
import com.stride.android.databinding.ActivitySignupBinding;

/** XML and binding owner for the account-creation screen. */
final class SignupScreen {
    final ActivitySignupBinding binding;
    SignupScreen(LayoutInflater inflater) { binding = ActivitySignupBinding.inflate(inflater); }
    EditText name() { return binding.nameInput; }
    EditText email() { return binding.emailInput; }
    EditText password() { return binding.passwordInput; }
    EditText confirmPassword() { return binding.confirmPasswordInput; }
    Button createAccount() { return binding.createAccountButton; }
    Button backToSignIn() { return binding.backToSignInButton; }
}
