package com.stride.android;

import android.view.LayoutInflater;
import android.widget.Button;
import android.widget.EditText;
import com.stride.android.databinding.ActivityLoginBinding;

/** XML and binding owner for the sign-in screen. */
final class LoginScreen {
    final ActivityLoginBinding binding;
    LoginScreen(LayoutInflater inflater) { binding = ActivityLoginBinding.inflate(inflater); }
    EditText email() { return binding.emailInput; }
    EditText password() { return binding.passwordInput; }
    Button signIn() { return binding.signInButton; }
    Button createAccount() { return binding.createAccountButton; }
}
