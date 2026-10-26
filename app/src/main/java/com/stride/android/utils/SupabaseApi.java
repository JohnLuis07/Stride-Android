package com.stride.android.utils;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.net.ssl.SSLException;

/** Authenticated Supabase REST, email/Google sessions, and protected Edge Functions. */
public final class SupabaseApi {
    private static final String BASE = "https://aenzjijrepswtcihtkgv.supabase.co/";
    private static final String KEY = "sb_publishable_gbSYQ22WYUPrpj_jvXn6LQ_Y4idkMPx";
    private static final String SESSION_KEY = "stride.google.session.v1";
    private static final String AUTH_LOG_TAG = "StrideAuth";
    public static final String SESSION_EXPIRED = "STRIDE_SESSION_EXPIRED";
    public static final String SESSION_STORAGE_FAILED = "STRIDE_SESSION_STORAGE_FAILED";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService functionExecutor = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object sessionLock = new Object();
    private final AtomicFile sessionFile;
    private volatile boolean closed;
    private volatile int generation;
    private volatile AuthSession session;
    private volatile boolean googleSessionRequired = true;

    public interface Callback {
        void done(JSONArray data, String error);
    }

    public interface AuthCallback {
        void done(JSONObject user, String error);
    }

    public interface JsonCallback {
        void done(JSONObject data, String error);
    }

    public SupabaseApi(Context context) {
        // Exclude device-bound ciphertext from Android backup and device transfers.
        sessionFile = new AtomicFile(new File(context.getNoBackupFilesDir(), "google_session"));
    }

    public boolean hasSavedGoogleSession() {
        return hasSavedSession();
    }

    public boolean hasSavedSession() {
        synchronized (sessionLock) {
            return sessionFile.getBaseFile().exists();
        }
    }

    public boolean isGoogleSession() {
        AuthSession current = session;
        JSONObject metadata = current == null ? null : current.user.optJSONObject("app_metadata");
        return metadata != null && "google".equals(metadata.optString("provider"));
    }

    public boolean isAuthenticated() {
        return session != null;
    }

    public int sessionGeneration() { return generation; }

    public void signInWithEmail(String email, String password, AuthCallback callback) {
        googleSessionRequired = true;
        authenticate("password", FeatureUi.obj("email", email, "password", password), callback);
    }

    public void signUpWithEmail(String name, String email, String password, AuthCallback callback) {
        authAction("POST", "auth/v1/signup", FeatureUi.obj("email", email, "password", password,
                "data", FeatureUi.obj("full_name", name)), false, callback);
    }

    public void verifyEmailCode(String email, String code, String type, AuthCallback callback) {
        authAction("POST", "auth/v1/verify", FeatureUi.obj("email", email, "token", code, "type", type), false, callback);
    }

    public void sendVerification(String email, String type, AuthCallback callback) {
        boolean recovery = "recovery".equals(type);
        authAction("POST", recovery ? "auth/v1/recover" : "auth/v1/resend",
                recovery ? FeatureUi.obj("email", email) : FeatureUi.obj("email", email, "type", type), "email_change".equals(type), callback);
    }

    public void updatePassword(String password, AuthCallback callback) {
        authAction("PUT", "auth/v1/user", FeatureUi.obj("password", password), true, callback);
    }

    public void updateEmail(String email, AuthCallback callback) {
        authAction("PUT", "auth/v1/user", FeatureUi.obj("email", email), true, callback);
    }

    public void loadIdentity(AuthCallback callback) {
        authAction("GET", "auth/v1/user", null, true, callback);
    }

    public void cancelPendingRequests() {
        synchronized (sessionLock) {
            generation++;
        }
    }

    private void authAction(String method, String endpoint, JSONObject body, boolean authenticated, AuthCallback callback) {
        int request = generation;
        executor.execute(() -> {
            JSONObject value = null;
            String error = null;
            try {
                checkGeneration(request);
                String payload = body == null ? null : body.toString();
                HttpResult response = authenticated ? authorizedRequest(method, endpoint, payload, request)
                        : send(method, endpoint, payload, null);
                response.requireSuccess();
                value = response.body.isEmpty() ? new JSONObject() : new JSONObject(response.body);
                if (!value.optString("access_token").isEmpty()) {
                    keepSession(new AuthSession(value), request);
                }
            } catch (Exception failure) {
                error = safeAuthError(failure);
            }
            deliverAuth(request, value, error, callback);
        });
    }

    public void restoreSession(AuthCallback callback) {
        restoreGoogleSession(callback);
    }

    public void signInWithGoogle(String idToken, String nonce, AuthCallback callback) {
        googleSessionRequired = true;
        authenticate("id_token", FeatureUi.obj("provider", "google", "id_token", idToken,
                "nonce", nonce), callback);
    }

    public void restoreGoogleSession(AuthCallback callback) {
        googleSessionRequired = true;
        int request = generation;
        executor.execute(() -> {
            JSONObject user = null;
            String error = null;
            try {
                String refreshToken;
                synchronized (sessionLock) {
                    checkGeneration(request);
                    refreshToken = readRefreshToken();
                }
                user = exchange("refresh_token", FeatureUi.obj("refresh_token", refreshToken),
                        request).user;
            } catch (Exception failure) {
                error = safeAuthError(failure);
            }
            deliverAuth(request, user, error, callback);
        });
    }

    /** The server resolves ownership using auth.uid(); the client never matches by email. */
    public void resolveGoogleProfile(AuthCallback callback) {
        resolveProfile(callback);
    }

    public void resolveProfile(AuthCallback callback) {
        int request = generation;
        executor.execute(() -> {
            JSONObject profile = null;
            String error = null;
            try {
                checkGeneration(request);
                if (session == null) {
                    throw new IllegalStateException(SESSION_EXPIRED);
                }
                HttpResult response = authorizedRequest("POST", "rest/v1/rpc/resolve_profile",
                        "{}", request);
                response.requireSuccess();
                profile = new JSONObject(response.body);
                if (profile.optString("id").isEmpty() || profile.optString("email").isEmpty()) {
                    throw new IllegalStateException("STRIDE_PROFILE_UNAVAILABLE");
                }
            } catch (Exception failure) {
                error = safeAuthError(failure);
            }
            deliverAuth(request, profile, error, callback);
        });
    }

    private void authenticate(String grant, JSONObject body, AuthCallback callback) {
        int request = generation;
        executor.execute(() -> {
            JSONObject user = null;
            String error = null;
            try {
                user = exchange(grant, body, request).user;
            } catch (Exception failure) {
                error = safeAuthError(failure);
            }
            deliverAuth(request, user, error, callback);
        });
    }

    private AuthSession exchange(String grant, JSONObject body, int request) throws Exception {
        checkGeneration(request);
        HttpResult response = send("POST", "auth/v1/token?grant_type=" + grant,
                body.toString(), null);
        if ("refresh_token".equals(grant) && (response.code == 400 || response.code == 401)) {
            synchronized (sessionLock) {
                checkGeneration(request);
                session = null;
                sessionFile.delete();
            }
            throw new IllegalStateException(SESSION_EXPIRED);
        }
        response.requireSuccess();
        AuthSession updated = new AuthSession(new JSONObject(response.body));
        keepSession(updated, request);
        return updated;
    }

    private void keepSession(AuthSession updated, int request) throws Exception {
        synchronized (sessionLock) {
            checkGeneration(request);
            try {
                saveRefreshToken(updated.refreshToken);
            } catch (Exception failure) {
                throw new IllegalStateException(SESSION_STORAGE_FAILED);
            }
            session = updated;
        }
    }

    /** Keys remain on the server. Function calls always carry a verified Auth JWT. */
    public void invokeFunction(String name, JSONObject body, JsonCallback callback) {
        if (!"notes-ai".equals(name)) {
            callback.done(null, "INVALID_ACTION");
            return;
        }
        int request = generation;
        functionExecutor.execute(() -> {
            JSONObject data = null;
            String error = null;
            try {
                HttpResult response = authorizedRequest("POST", "functions/v1/" + name, body.toString(), request);
                response.requireSuccess();
                data = new JSONObject(response.body);
            } catch (Exception failure) {
                error = safeError(failure);
            }
            JSONObject result = data;
            String message = error;
            main.post(() -> {
                if (!closed && request == generation) {
                    callback.done(result, message);
                }
            });
        });
    }

    public static boolean isStorageUrl(String value) {
        try {
            URL url = new URL(value);
            return "https".equals(url.getProtocol()) && new URL(BASE).getHost().equals(url.getHost())
                    && url.getPort() == -1 && url.getUserInfo() == null && url.getPath().startsWith("/storage/v1/");
        } catch (Exception failure) {
            return false;
        }
    }

    public void get(String table, String query, Callback callback) {
        request("GET", table + (query.isEmpty() ? "" : "?" + query), null, callback);
    }

    public void insert(String table, JSONObject value, Callback callback) {
        request("POST", table, new JSONArray().put(value).toString(), callback);
    }

    public void update(String table, String query, JSONObject value, Callback callback) {
        request("PATCH", table + "?" + query, value.toString(), callback);
    }

    public void delete(String table, String query, Callback callback) {
        request("DELETE", table + "?" + query, null, callback);
    }

    public static String eq(String field, String value) {
        return field + "=eq." + encode(value);
    }

    public static String and(String... values) {
        return android.text.TextUtils.join("&", values);
    }

    public static String encode(String value) {
        try {
            // The Charset overload is unavailable on older supported Android versions.
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException error) {
            throw new IllegalStateException("UTF-8 is required on Android", error);
        }
    }

    private void request(String method, String endpoint, String body, Callback callback) {
        int request = generation;
        executor.execute(() -> {
            JSONArray rows = new JSONArray();
            String error = null;
            try {
                checkGeneration(request);
                HttpResult response = authorizedRequest(method, "rest/v1/" + endpoint, body, request);
                response.requireSuccess();
                if (!response.body.isEmpty()) {
                    rows = new JSONArray(response.body);
                }
            } catch (Exception failure) {
                error = safeError(failure);
            }
            JSONArray result = rows;
            String message = error;
            main.post(() -> {
                if (!closed && request == generation) {
                    callback.done(result, message);
                }
            });
        });
    }

    private HttpResult authorizedRequest(String method, String endpoint, String body, int request)
            throws Exception {
        checkGeneration(request);
        AuthSession current;
        synchronized (sessionLock) {
            current = session;
            if (current == null && googleSessionRequired) {
                throw new IllegalStateException(SESSION_EXPIRED);
            }
            if (current != null && current.expiresAt <= System.currentTimeMillis() / 1000L + 60L) {
                current = exchange("refresh_token", FeatureUi.obj("refresh_token", current.refreshToken), request);
            }
        }
        checkGeneration(request);
        HttpResult response = send(method, endpoint, body, current == null ? null : current.accessToken);
        // A rejected token cannot authorize a mutation. Retry only once after refreshing it.
        if (response.code == 401 && current != null) {
            synchronized (sessionLock) {
                if (session != null && !session.accessToken.equals(current.accessToken)) {
                    current = session;
                } else {
                    current = exchange("refresh_token", FeatureUi.obj("refresh_token", current.refreshToken), request);
                }
            }
            checkGeneration(request);
            response = send(method, endpoint, body, current.accessToken);
        }
        return response;
    }

    /** Drop local credentials synchronously; revoke this device's session when online. */
    public void signOutGoogle() {
        AuthSession previous;
        synchronized (sessionLock) {
            generation++;
            previous = session;
            session = null;
            googleSessionRequired = true;
            sessionFile.delete();
        }
        if (previous != null && !closed) {
            executor.execute(() -> {
                try {
                    send("POST", "auth/v1/logout?scope=local", null, previous.accessToken);
                } catch (Exception ignored) {
                    // Offline sign-out still removes all locally persisted credentials.
                }
            });
        }
    }

    public void close() {
        closed = true;
        executor.shutdownNow();
        functionExecutor.shutdownNow();
        main.removeCallbacksAndMessages(null);
    }

    private void checkGeneration(int request) {
        if (closed || request != generation) {
            throw new IllegalStateException(SESSION_EXPIRED);
        }
    }

    private void deliverAuth(int request, JSONObject user, String error, AuthCallback callback) {
        main.post(() -> {
            if (!closed && request == generation) {
                callback.done(user, error);
            }
        });
    }

    private static HttpResult send(String method, String endpoint, String body, String accessToken)
            throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(BASE + endpoint).openConnection();
        try {
            connection.setRequestMethod(method);
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(endpoint.startsWith("functions/v1/") ? 120000 : 15000);
            connection.setRequestProperty("apikey", KEY);
            // Publishable keys are not JWTs. Google requests use a Supabase access token.
            if (accessToken != null) {
                connection.setRequestProperty("Authorization", "Bearer " + accessToken);
            }
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            if (endpoint.startsWith("rest/v1/")) {
                connection.setRequestProperty("Prefer", "return=representation");
            }
            if (body != null) {
                connection.setDoOutput(true);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = connection.getResponseCode();
            InputStream input = code >= 200 && code < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            StringBuilder text = new StringBuilder();
            if (input != null) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(input, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        text.append(line);
                    }
                }
            }
            return new HttpResult(code, text.toString());
        } finally {
            connection.disconnect();
        }
    }

    private SecretKey sessionKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(SESSION_KEY)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,
                    "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(SESSION_KEY,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return (SecretKey) store.getKey(SESSION_KEY, null);
    }

    private void saveRefreshToken(String refreshToken) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, sessionKey());
        byte[] encrypted = cipher.doFinal(refreshToken.getBytes(StandardCharsets.UTF_8));
        byte[] iv = cipher.getIV();
        FileOutputStream output = null;
        try {
            output = sessionFile.startWrite();
            output.write(iv.length);
            output.write(iv);
            output.write(encrypted);
            sessionFile.finishWrite(output);
        } catch (Exception failure) {
            if (output != null) {
                sessionFile.failWrite(output);
            }
            throw failure;
        }
    }

    private String readRefreshToken() throws Exception {
        try {
            byte[] data = sessionFile.readFully();
            if (data.length < 30 || data.length > 16384 || data[0] != 12) {
                throw new IllegalStateException(SESSION_EXPIRED);
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, sessionKey(), new GCMParameterSpec(128, data, 1, 12));
            return new String(cipher.doFinal(data, 13, data.length - 13), StandardCharsets.UTF_8);
        } catch (Exception failure) {
            sessionFile.delete();
            throw new IllegalStateException(SESSION_EXPIRED);
        }
    }

    private static String safeError(Exception failure) {
        return failure instanceof IllegalStateException && failure.getMessage() != null
                ? failure.getMessage() : "Network request failed";
    }

    /** Auth callbacks expose only status, a bounded machine code, and an allowlisted reason. */
    private static String safeAuthError(Exception failure) {
        int status = 0;
        String code;
        String reason = "";
        if (failure instanceof HttpFailure) {
            HttpFailure response = (HttpFailure) failure;
            status = response.status;
            JSONObject details;
            try {
                details = new JSONObject(response.responseBody);
            } catch (org.json.JSONException ignored) {
                details = new JSONObject();
            }
            code = machineCode(details.optString("error_code"));
            if (code.isEmpty()) code = machineCode(details.optString("code"));
            if (code.isEmpty()) code = machineCode(details.optString("error"));
            if (code.isEmpty()) code = "http_error";
            String description = details.optString("msg", "") + " " + details.optString("message", "")
                    + " " + details.optString("error_description", "");
            reason = authReason(description);
        } else if (failure instanceof SocketTimeoutException) {
            code = "request_timeout";
        } else if (failure instanceof SSLException) {
            code = "secure_connection_failed";
        } else if (failure instanceof IOException) {
            code = "network_error";
        } else if (failure instanceof org.json.JSONException) {
            code = "invalid_response";
        } else {
            String localReason = authReason(failure.getMessage() == null ? "" : failure.getMessage());
            code = localReason.isEmpty() ? "client_error" : localReason;
        }
        // Never log a body, endpoint/query string, email, password, verification token, or Throwable.
        Log.w(AUTH_LOG_TAG, "status=" + status + " code=" + code + (reason.isEmpty() ? "" : " reason=" + reason));
        return FeatureUi.obj("status", status, "code", code, "reason", reason).toString();
    }

    private static String machineCode(String value) {
        return value != null && !"null".equals(value)
                && value.matches("(?:[A-Za-z][A-Za-z0-9_]{0,79}|[0-9A-Z]{5})") ? value : "";
    }

    private static String authReason(String description) {
        // SQL RPC markers have no structured Auth code. Preserve only these known application markers.
        for (String marker : new String[]{SESSION_EXPIRED, SESSION_STORAGE_FAILED, "STRIDE_ACCOUNT_LINK_REQUIRED",
                "STRIDE_EMAIL_UNCONFIRMED", "STRIDE_PROFILE_UNAVAILABLE", "STRIDE_EMAIL_MANAGED"}) {
            if (description.contains(marker)) return marker;
        }
        String text = description.toLowerCase(Locale.ROOT);
        // Older Auth gateways supply a message without a machine code; retain safe classifications only.
        if ((text.contains("error sending") && (text.contains("email") || text.contains("mail")))
                || text.contains("smtp") || text.contains("error rendering email")
                || text.contains("error generating email")) return "email_delivery_failed";
        if (text.contains("database error")) return "auth_database_error";
        if (text.contains("email address not authorized")) return "email_address_not_authorized";
        if (text.contains("invalid api key") || text.contains("no api key found")) return "api_key_invalid";
        if (text.contains("user already registered")) return "user_already_exists";
        if (text.contains("invalid login credentials")) return "invalid_credentials";
        if (text.contains("email link is invalid")) return "otp_expired";
        if (text.contains("unacceptable audience")) return "google_configuration_invalid";
        if (text.contains("unsupported provider")) return "provider_disabled";
        return "";
    }

    private static final class AuthSession {
        final String accessToken;
        final String refreshToken;
        final long expiresAt;
        final JSONObject user;

        AuthSession(JSONObject value) {
            accessToken = value.optString("access_token", "");
            refreshToken = value.optString("refresh_token", "");
            user = value.optJSONObject("user");
            long lifetime = value.optLong("expires_in", 0L);
            if (accessToken.isEmpty() || refreshToken.isEmpty() || lifetime <= 0L
                    || user == null || user.optString("id", "").isEmpty()) {
                throw new IllegalStateException(SESSION_EXPIRED);
            }
            expiresAt = System.currentTimeMillis() / 1000L + lifetime;
        }
    }

    private static final class HttpResult {
        final int code;
        final String body;

        HttpResult(int code, String body) {
            this.code = code;
            this.body = body;
        }

        void requireSuccess() {
            if (code < 200 || code >= 300) {
                throw new HttpFailure(code, body);
            }
        }
    }

    private static final class HttpFailure extends IllegalStateException {
        final int status;
        final String responseBody;

        HttpFailure(int status, String body) {
            super(body.isEmpty() ? "Request failed (" + status + ")" : body);
            this.status = status;
            responseBody = body;
        }
    }
}
