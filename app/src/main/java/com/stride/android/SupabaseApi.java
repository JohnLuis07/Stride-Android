package com.stride.android;

import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Minimal REST client for the same Supabase tables used by the web app. */
final class SupabaseApi {
    private static final String BASE = "https://aenzjijrepswtcihtkgv.supabase.co/rest/v1/";
    private static final String KEY = "sb_publishable_gbSYQ22WYUPrpj_jvXn6LQ_Y4idkMPx";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    interface Callback { void done(JSONArray data, String error); }

    void get(String table, String query, Callback callback) { request("GET", table + (query.isEmpty() ? "" : "?" + query), null, callback); }
    void insert(String table, JSONObject value, Callback callback) {
        JSONArray values = new JSONArray(); values.put(value); request("POST", table, values.toString(), callback);
    }
    void update(String table, String query, JSONObject value, Callback callback) { request("PATCH", table + "?" + query, value.toString(), callback); }
    void delete(String table, String query, Callback callback) { request("DELETE", table + "?" + query, null, callback); }

    static String eq(String field, String value) { return field + "=eq." + encode(value); }
    static String and(String... values) { return android.text.TextUtils.join("&", values); }
    static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    private void request(String method, String endpoint, String body, Callback callback) {
        executor.execute(() -> {
            HttpURLConnection connection = null;
            JSONArray result = new JSONArray(); String error = null;
            try {
                connection = (HttpURLConnection) new URL(BASE + endpoint).openConnection();
                connection.setRequestMethod(method); connection.setConnectTimeout(12000); connection.setReadTimeout(15000);
                connection.setRequestProperty("apikey", KEY); connection.setRequestProperty("Authorization", "Bearer " + KEY);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("Prefer", "return=representation");
                if (body != null) { connection.setDoOutput(true); try (OutputStream stream = connection.getOutputStream()) { stream.write(body.getBytes(StandardCharsets.UTF_8)); } }
                int code = connection.getResponseCode();
                InputStream input = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
                String response = read(input);
                if (code < 200 || code >= 300) error = response.isEmpty() ? "Request failed (" + code + ")" : response;
                else if (!response.isEmpty()) result = new JSONArray(response);
            } catch (Exception exception) { error = exception.getMessage() == null ? "Network request failed" : exception.getMessage(); }
            finally { if (connection != null) connection.disconnect(); }
            JSONArray finalResult = result; String finalError = error;
            main.post(() -> callback.done(finalResult, finalError));
        });
    }

    private static String read(InputStream input) throws Exception {
        if (input == null) return ""; StringBuilder text = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input))) { String line; while ((line = reader.readLine()) != null) text.append(line); }
        return text.toString();
    }
}
