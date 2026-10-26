package com.stride.android.utils;

import android.app.Activity;
import android.content.Context;
import android.os.CancellationSignal;
import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.credentials.ClearCredentialStateRequest;
import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.ClearCredentialException;
import androidx.credentials.exceptions.GetCredentialCancellationException;
import androidx.credentials.exceptions.GetCredentialException;
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException;
import androidx.credentials.exceptions.GetCredentialUnsupportedException;
import androidx.credentials.exceptions.NoCredentialException;

import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;
import com.stride.android.R;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/** Requests a Google identity; only Supabase is allowed to validate and trust it. */
public final class GoogleSignIn {
    public interface Result {
        // A zero error with no token means the user dismissed the picker.
        void done(String idToken, String nonce, int errorResource);
    }

    private final Activity activity;
    private final CredentialManager credentials;
    private CancellationSignal cancellation;

    public GoogleSignIn(Activity activity) {
        this.activity = activity;
        credentials = CredentialManager.create(activity.getApplicationContext());
    }

    public void signIn(Result result) {
        String clientId = activity.getString(R.string.google_web_client_id).trim();
        if (clientId.isEmpty()) {
            result.done(null, null, R.string.google_not_configured);
            return;
        }
        cancel();
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String nonce = Base64.encodeToString(random, Base64.NO_WRAP | Base64.URL_SAFE);
        CancellationSignal request = new CancellationSignal();
        cancellation = request;
        try {
            GetSignInWithGoogleOption option = new GetSignInWithGoogleOption.Builder(clientId)
                    .setNonce(sha256(nonce)).build();
            GetCredentialRequest credentialRequest = new GetCredentialRequest.Builder()
                    .addCredentialOption(option).build();
            credentials.getCredentialAsync(activity, credentialRequest, request,
                    ContextCompat.getMainExecutor(activity),
                    new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                        @Override
                        public void onResult(GetCredentialResponse response) {
                            if (request.isCanceled()) {
                                return;
                            }
                            cancellation = null;
                            Credential credential = response.getCredential();
                            if (credential instanceof CustomCredential
                                    && GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                                            .equals(credential.getType())) {
                                String token;
                                try {
                                    token = GoogleIdTokenCredential.createFrom(
                                            credential.getData()).getIdToken();
                                } catch (Exception ignored) {
                                    // Never surface credentials or provider response data to the UI.
                                    result.done(null, null, R.string.google_sign_in_failed);
                                    return;
                                }
                                result.done(token, nonce, 0);
                                return;
                            }
                            result.done(null, null, R.string.google_sign_in_failed);
                        }

                        @Override
                        public void onError(@NonNull GetCredentialException error) {
                            if (request.isCanceled()) {
                                return;
                            }
                            cancellation = null;
                            int message = R.string.google_sign_in_failed;
                            if (error instanceof GetCredentialCancellationException) {
                                message = 0;
                            } else if (error instanceof NoCredentialException) {
                                message = R.string.google_no_account;
                            } else if (error instanceof GetCredentialProviderConfigurationException
                                    || error instanceof GetCredentialUnsupportedException) {
                                message = R.string.google_provider_unavailable;
                            }
                            result.done(null, null, message);
                        }
                    });
        } catch (RuntimeException error) {
            cancellation = null;
            result.done(null, null, R.string.google_provider_unavailable);
        }
    }

    public void cancel() {
        if (cancellation != null) {
            cancellation.cancel();
            cancellation = null;
        }
    }

    public static void clearSession(Context context) {
        try {
            CredentialManager.create(context).clearCredentialStateAsync(
                    new ClearCredentialStateRequest(), null, ContextCompat.getMainExecutor(context),
                    new CredentialManagerCallback<Void, ClearCredentialException>() {
                        @Override
                        public void onResult(Void unused) {
                            // Local and Supabase sign-out do not depend on provider availability.
                        }

                        @Override
                        public void onError(@NonNull ClearCredentialException error) {
                            // The explicit Google button still allows choosing another account.
                        }
                    });
        } catch (RuntimeException ignored) {
            // Devices without a credential provider must still be able to sign out locally.
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            char[] hex = "0123456789abcdef".toCharArray();
            StringBuilder output = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                output.append(hex[(item & 0xff) >>> 4]).append(hex[item & 0xf]);
            }
            return output.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is required on Android", error);
        }
    }
}
