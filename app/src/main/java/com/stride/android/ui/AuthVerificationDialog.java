package com.stride.android.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Handler;
import android.os.Looper;
import android.util.Patterns;
import android.view.View;

import com.stride.android.R;
import com.stride.android.databinding.DialogAuthVerificationBinding;
import com.stride.android.viewmodel.SessionViewModel;

/** In-app email codes and password recovery; never persists a password or code. */
final class AuthVerificationDialog {
    private final Activity activity;
    private final SessionViewModel session;
    private final DialogAuthVerificationBinding binding;
    private final AlertDialog dialog;
    private final String type;
    private final Runnable success;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String email;
    private boolean sent;
    private boolean verified;
    private boolean busy;
    private boolean closed;
    private boolean completed;
    private boolean coolingDown;

    AuthVerificationDialog(Activity activity, SessionViewModel session, String email, String type,
            Runnable success, Runnable onClose) {
        this.activity = activity;
        this.session = session;
        this.email = email;
        this.type = type;
        this.success = success;
        sent = !"recovery".equals(type);
        binding = DialogAuthVerificationBinding.inflate(activity.getLayoutInflater());
        binding.emailInput.setText(email);
        binding.resendButton.setOnClickListener(v -> sendCode());
        int title = "recovery".equals(type) ? R.string.auth_recovery_title
                : "email_change".equals(type) ? R.string.auth_email_change_title : R.string.auth_verify_title;
        dialog = new AlertDialog.Builder(activity).setTitle(title).setView(binding.getRoot())
                .setNegativeButton(R.string.auth_cancel, null).setPositiveButton(R.string.auth_verify, null).create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> act());
            render();
        });
        dialog.setOnDismissListener(d -> {
            closed = true;
            handler.removeCallbacksAndMessages(null);
            binding.passwordInput.setText("");
            binding.confirmInput.setText("");
            binding.codeInput.setText("");
            if (!completed) session.cancelVerification();
            onClose.run();
        });
    }

    void show() { dialog.show(); }
    void close() { if (!closed) dialog.dismiss(); }

    private void act() {
        if (closed || busy) return;
        binding.errorText.setVisibility(View.GONE);
        if (!sent) {
            sendCode();
            return;
        }
        if (verified) {
            String password = binding.passwordInput.getText().toString();
            if (password.length() < 8) { error(activity.getString(R.string.auth_password_short)); return; }
            if (!password.equals(binding.confirmInput.getText().toString())) {
                error(activity.getString(R.string.auth_password_mismatch)); return;
            }
            busy = true;
            render();
            session.finishRecovery(password, this::finish);
            return;
        }
        String code = binding.codeInput.getText().toString().trim();
        if (!code.matches("[0-9]{6,10}")) { error(activity.getString(R.string.auth_code_invalid)); return; }
        busy = true;
        render();
        session.verifyCode(email, code, type, error -> {
            if (closed) return;
            busy = false;
            if (error != null) {
                error(error);
            } else if ("recovery".equals(type)) {
                verified = true;
                binding.codeInput.setText("");
            } else {
                complete();
                return;
            }
            render();
        });
    }

    private void sendCode() {
        if (closed || busy || coolingDown || "email_change".equals(type)) return;
        email = binding.emailInput.getText().toString().trim();
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) { error(activity.getString(R.string.auth_email_invalid)); return; }
        busy = true;
        render();
        session.sendCode(email, type, error -> {
            if (closed) return;
            busy = false;
            if (error != null) error(error);
            else {
                sent = true;
                coolingDown = true;
                error(activity.getString(R.string.auth_code_sent));
                handler.postDelayed(() -> { coolingDown = false; if (!closed) render(); }, 30000);
            }
            render();
        });
    }

    private void finish(String error) {
        if (closed) return;
        busy = false;
        if (error == null) complete();
        else { error(error); render(); }
    }

    private void complete() {
        completed = true;
        dialog.dismiss();
        success.run();
    }

    private void error(String text) {
        binding.errorText.setText(SessionViewModel.EMAIL_CONFIRMATION_REQUIRED.equals(text)
                ? activity.getString(R.string.auth_code_invalid) : text);
        binding.errorText.setVisibility(View.VISIBLE);
    }

    private void render() {
        if (closed || !dialog.isShowing()) return;
        binding.instructions.setText(verified ? activity.getString(R.string.auth_new_password_hint)
                : !sent ? activity.getString(R.string.auth_recovery_request)
                : activity.getString("email_change".equals(type) ? R.string.auth_change_instructions
                        : R.string.auth_code_instructions, email));
        binding.emailInput.setEnabled(!busy && !sent);
        binding.emailInput.setVisibility(verified ? View.GONE : View.VISIBLE);
        binding.codeInput.setVisibility(sent && !verified ? View.VISIBLE : View.GONE);
        binding.codeInput.setEnabled(!busy);
        binding.passwordInput.setVisibility(verified ? View.VISIBLE : View.GONE);
        binding.confirmInput.setVisibility(verified ? View.VISIBLE : View.GONE);
        binding.passwordInput.setEnabled(!busy);
        binding.confirmInput.setEnabled(!busy);
        binding.resendButton.setVisibility(sent && !verified && !"email_change".equals(type) ? View.VISIBLE : View.GONE);
        binding.resendButton.setEnabled(!busy && !coolingDown);
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(!busy);
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setText(busy ? R.string.auth_working
                : verified ? R.string.auth_update_password : sent ? R.string.auth_verify : R.string.auth_send_code);
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setEnabled(!busy);
        dialog.setCancelable(!busy);
        dialog.setCanceledOnTouchOutside(false);
    }
}
