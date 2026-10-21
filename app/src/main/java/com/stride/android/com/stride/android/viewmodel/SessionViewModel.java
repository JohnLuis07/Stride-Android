package com.stride.android.viewmodel;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;

import com.stride.android.utils.FeatureUi;
import com.stride.android.utils.SupabaseApi;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.UUID;

/** Owns the API client and the existing users-table authentication flow. */
public final class SessionViewModel extends AndroidViewModel {
    public interface Result {
        void done(String error);
    }

    private final SupabaseApi api = new SupabaseApi();
    private final SharedPreferences preferences;
    private JSONObject user;
    private int generation;

    public SessionViewModel(@NonNull Application application) {
        super(application);
        preferences = application.getSharedPreferences("stride", Context.MODE_PRIVATE);
    }

    public SupabaseApi api() {
        return api;
    }

    public JSONObject user() {
        return user;
    }

    public void restore(Result result) {
        if (user != null) {
            result.done(null);
            return;
        }
        String email = preferences.getString("userEmail", "");
        if (email.isEmpty()) {
            result.done("No saved session");
            return;
        }
        int request = ++generation;
        api.get("users", SupabaseApi.and("select=*", SupabaseApi.eq("email", email)),
                (rows, error) -> {
                    if (request != generation) {
                        return;
                    }
                    user = error == null && rows.length() == 1 ? rows.optJSONObject(0) : null;
                    result.done(user == null ? "Session unavailable" : null);
                });
    }

    public void signIn(String email, String password, Result result) {
        int request = ++generation;
        api.get("users", SupabaseApi.and("select=*", SupabaseApi.eq("email", email),
                SupabaseApi.eq("password", password)), (rows, error) -> {
                    if (request != generation) {
                        return;
                    }
                    if (error != null || rows.length() != 1) {
                        result.done("Invalid email or password");
                        return;
                    }
                    user = rows.optJSONObject(0);
                    preferences.edit().putString("userEmail", email).apply();
                    result.done(null);
                });
    }

    public void signUp(String name, String email, String password, Result result) {
        api.insert("users", FeatureUi.obj("id", UUID.randomUUID().toString(),
                "full_name", name, "email", email, "password", password), (rows, error) ->
                result.done(error == null ? null : error.contains("23505")
                        ? "Email already exists" : "Could not create account"));
    }

    public void saveProfile(String name, String email, Result result) {
        int request = generation;
        api.update("users", SupabaseApi.eq("id", user.optString("id")),
                FeatureUi.obj("full_name", name, "email", email), (rows, error) -> {
                    if (request != generation) {
                        return;
                    }
                    if (error == null) {
                        merge(FeatureUi.obj("full_name", name, "email", email));
                        preferences.edit().putString("userEmail", email).apply();
                    }
                    result.done(error);
                });
    }

    public void saveFeatures(JSONArray features, Result result) {
        int request = generation;
        api.update("users", SupabaseApi.eq("id", user.optString("id")),
                FeatureUi.obj("features", features), (rows, error) -> {
                    if (request != generation) {
                        return;
                    }
                    if (error == null) {
                        merge(FeatureUi.obj("features", features));
                    }
                    result.done(error);
                });
    }

    private void merge(JSONObject changes) {
        if (user == null) {
            return;
        }
        Iterator<String> keys = changes.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            try {
                user.put(key, changes.opt(key));
            } catch (JSONException exception) {
                throw new IllegalStateException("Cannot update session field " + key, exception);
            }
        }
    }

    public void signOut() {
        generation++;
        user = null;
        preferences.edit().remove("userEmail").apply();
    }

    @Override
    protected void onCleared() {
        api.close();
    }
}
