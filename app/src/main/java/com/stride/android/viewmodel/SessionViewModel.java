package com.stride.android.viewmodel;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;

import com.stride.android.R;
import com.stride.android.utils.FeatureUi;
import com.stride.android.utils.GoogleSignIn;
import com.stride.android.utils.SupabaseApi;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;

/** Verified email/password and optional Google sessions share the same profile mapping. */
public final class SessionViewModel extends AndroidViewModel {
    public interface Result {
        void done(String error);
    }

    public static final String EMAIL_CONFIRMATION_REQUIRED = "EMAIL_CONFIRMATION_REQUIRED";
    public static final String EMAIL_CHANGE_PENDING = "EMAIL_CHANGE_PENDING";
    private final SupabaseApi api;
    private final SharedPreferences preferences;
    private JSONObject user;
    private int generation;

    public SessionViewModel(@NonNull Application application) {
        super(application);
        api = new SupabaseApi(application);
        preferences = application.getSharedPreferences("stride", Context.MODE_PRIVATE);
        // Email alone is never sufficient proof to restore a session.
        preferences.edit().remove("userEmail").apply();
    }

    public SupabaseApi api() { return api; }
    public JSONObject user() { return user; }

    public void restore(Result result) {
        if (user != null) {
            result.done(null);
            return;
        }
        if (!api.hasSavedSession()) {
            result.done(message(R.string.auth_session_expired));
            return;
        }
        int request = ++generation;
        api.restoreSession((identity, error) -> {
            if (request != generation) return;
            if (error != null) {
                result.done(authError(error));
            } else {
                loadProfile(request, result);
            }
        });
    }

    public void signIn(String email, String password, Result result) {
        int request = ++generation;
        api.signInWithEmail(email, password, (identity, error) -> {
            if (request != generation) return;
            if (error != null) result.done(authError(error));
            else loadProfile(request, result);
        });
    }

    public void signUp(String name, String email, String password, Result result) {
        int request = ++generation;
        api.signUpWithEmail(name, email, password, (response, error) -> {
            if (request != generation) return;
            if (error != null) result.done(authError(error));
            else if (response == null || response.optString("access_token").isEmpty()) {
                result.done(EMAIL_CONFIRMATION_REQUIRED);
            } else loadProfile(request, result);
        });
    }

    public void signInWithGoogle(String idToken, String nonce, Result result) {
        int request = ++generation;
        api.signInWithGoogle(idToken, nonce, (identity, error) -> {
            if (request != generation) return;
            if (error != null) {
                api.signOutGoogle();
                result.done(authError(error));
            } else loadProfile(request, result);
        });
    }

    private void loadProfile(int request, Result result) {
        api.resolveProfile((profile, error) -> {
            if (request != generation) return;
            if (error != null || profile == null) {
                api.signOutGoogle();
                user = null;
                result.done(authError(error == null ? "STRIDE_PROFILE_UNAVAILABLE" : error));
            } else {
                user = profile;
                preferences.edit().remove("userEmail").apply();
                result.done(null);
            }
        });
    }

    public void sendCode(String email, String type, Result result) {
        int request = generation;
        api.sendVerification(email, type, (value, error) -> {
            if (request == generation) result.done(error == null ? null : authError(error));
        });
    }

    public void verifyCode(String email, String code, String type, Result result) {
        int request = ++generation;
        api.verifyEmailCode(email, code, type, (value, error) -> {
            if (request != generation) return;
            if ("email_change".equals(type)) {
                api.loadIdentity((identity, identityError) -> {
                    if (request != generation) return;
                    if (identityError != null) result.done(authError(identityError));
                    else if (identity != null && email.equalsIgnoreCase(identity.optString("email"))) loadProfile(request, result);
                    else result.done(error == null ? message(R.string.auth_change_incomplete) : authError(error));
                });
            } else if (error != null) {
                result.done(authError(error));
            } else if ("recovery".equals(type)) {
                // Recovery identity is retained only until the new password is saved.
                result.done(null);
            } else loadProfile(request, result);
        });
    }

    public void finishRecovery(String password, Result result) {
        int request = generation;
        api.updatePassword(password, (value, error) -> {
            if (request != generation) return;
            if (error != null) result.done(authError(error));
            else loadProfile(request, result);
        });
    }

    public void cancelVerification() {
        generation++;
        api.cancelPendingRequests();
        if (user == null) api.signOutGoogle();
    }

    public void cancelGoogleSignIn() {
        generation++;
        api.signOutGoogle();
    }

    private String authError(String error) {
        if (error == null) return message(R.string.auth_request_failed);
        String code = error;
        String reason = "";
        int status = 0;
        try {
            JSONObject details = new JSONObject(error);
            code = details.optString("code", "");
            reason = details.optString("reason", "");
            status = details.optInt("status", 0);
        } catch (JSONException ignored) {
            // Local application markers can arrive before any HTTP request was made.
        }
        String result = knownAuthError(code);
        if (result == null && !reason.isEmpty()) result = knownAuthError(reason);
        if (result != null) return result;
        if (status == 429) return message(R.string.auth_rate_limit);
        if (status > 0) {
            String reference = "HTTP " + status;
            if (code.matches("(?:[A-Za-z][A-Za-z0-9_]{0,79}|[0-9A-Z]{5})")) reference += " / " + code;
            return getApplication().getString(R.string.auth_service_error, reference);
        }
        return message(R.string.auth_request_failed);
    }

    private String knownAuthError(String code) {
        switch (code) {
            case "email_not_confirmed": case "STRIDE_EMAIL_UNCONFIRMED":
                return EMAIL_CONFIRMATION_REQUIRED;
            case "STRIDE_ACCOUNT_LINK_REQUIRED": return message(R.string.auth_migration_required);
            case "invalid_credentials": return message(R.string.auth_invalid_credentials);
            case "weak_password": return message(R.string.auth_password_weak);
            case "same_password": return message(R.string.auth_password_unchanged);
            case "otp_expired": case "token_expired": return message(R.string.auth_code_invalid);
            case "over_email_send_rate_limit": return message(R.string.auth_email_rate_limit);
            case "over_request_rate_limit": return message(R.string.auth_rate_limit);
            case "email_address_not_authorized": return message(R.string.auth_email_not_authorized);
            case "email_address_invalid": return message(R.string.auth_email_unsupported);
            case "email_delivery_failed": return message(R.string.auth_email_delivery_failed);
            case "auth_database_error": return message(R.string.auth_database_error);
            case "user_already_exists": case "email_exists": return message(R.string.auth_account_exists);
            case "signup_disabled": return message(R.string.auth_signup_disabled);
            case "email_provider_disabled": return message(R.string.auth_email_disabled);
            case "captcha_failed": case "captcha_required": return message(R.string.auth_verification_unavailable);
            case "reauthentication_needed": case "session_expired": case "session_not_found":
            case SupabaseApi.SESSION_EXPIRED: return message(R.string.auth_session_expired);
            case SupabaseApi.SESSION_STORAGE_FAILED: return message(R.string.google_session_storage_failed);
            case "PGRST202": case "PGRST204": case "42703": case "42883": case "api_key_invalid":
            case "STRIDE_PROFILE_UNAVAILABLE": return message(R.string.auth_setup_required);
            case "STRIDE_EMAIL_MANAGED": return message(R.string.google_email_managed);
            case "provider_disabled": case "oauth_provider_not_supported": case "google_configuration_invalid":
                return message(R.string.google_backend_not_configured);
            case "request_timeout": return message(R.string.auth_request_timeout);
            case "secure_connection_failed": return message(R.string.auth_secure_connection_failed);
            case "network_error": return message(R.string.auth_network_error);
            case "invalid_response": return message(R.string.auth_invalid_response);
            case "user_banned": return message(R.string.auth_account_unavailable);
            default: return null;
        }
    }

    private String message(int resource) { return getApplication().getString(resource); }

    public void saveProfile(String name, String email, Result result) {
        if (user == null) {
            result.done(message(R.string.auth_session_expired));
            return;
        }
        boolean changingEmail = !email.equals(user.optString("email"));
        if (changingEmail && api.isGoogleSession()) {
            result.done(message(R.string.google_email_managed));
            return;
        }
        int request = generation;
        Runnable saveName = () -> api.update("users", SupabaseApi.eq("id", user.optString("id")),
                FeatureUi.obj("full_name", name), (rows, error) -> {
                    if (request != generation) return;
                    if (error == null && rows.length() == 1) {
                        merge(FeatureUi.obj("full_name", name));
                        result.done(changingEmail ? EMAIL_CHANGE_PENDING : null);
                    } else result.done(message(R.string.settings_profile_error));
                });
        if (changingEmail) {
            api.updateEmail(email, (value, error) -> {
                if (request != generation) return;
                if (error != null) result.done(authError(error));
                else saveName.run();
            });
        } else saveName.run();
    }

    public void saveFeatures(JSONArray features, Result result) {
        if (user == null) {
            result.done(message(R.string.auth_session_expired));
            return;
        }
        int request = generation;
        api.update("users", SupabaseApi.eq("id", user.optString("id")),
                FeatureUi.obj("features", features), (rows, error) -> {
                    if (request != generation) return;
                    if (error == null && rows.length() == 1) merge(FeatureUi.obj("features", features));
                    result.done(error);
                });
    }

    private void merge(JSONObject changes) {
        if (user == null) return;
        Iterator<String> keys = changes.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            try { user.put(key, changes.opt(key)); }
            catch (JSONException exception) { throw new IllegalStateException("Cannot update profile"); }
        }
    }

    public void signOut() {
        generation++;
        boolean googleSession = api.isGoogleSession();
        user = null;
        api.signOutGoogle();
        preferences.edit().remove("userEmail").apply();
        if (googleSession) GoogleSignIn.clearSession(getApplication());
    }

    @Override
    protected void onCleared() { api.close(); }
}
