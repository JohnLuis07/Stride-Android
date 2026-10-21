package com.stride.android.utils;

import android.app.Activity;
import android.widget.EditText;
import android.widget.Toast;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Formatting and form helpers shared by binding-backed screens. */
public final class FeatureUi {
    private final Activity activity;

    public FeatureUi(Activity activity) {
        this.activity = activity;
    }

    public void toast(String value) {
        Toast.makeText(activity, value, Toast.LENGTH_LONG).show();
    }

    public static String now() {
        return new SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
                Locale.US
        ).format(new Date());
    }

    public static String today() {
        return new SimpleDateFormat(
                "yyyy-MM-dd",
                Locale.US
        ).format(new Date());
    }

    public static String strip(String value) {
        return value
                .replaceAll("<[^>]*>", "")
                .replace("&nbsp;", " ");
    }

    public static Object emptyNull(EditText value) {
        String text = value
                .getText()
                .toString()
                .trim();

        return text.isEmpty()
                ? JSONObject.NULL
                : text;
    }

    public static double number(
            EditText value,
            double fallback
    ) {
        try {
            return Double.parseDouble(
                    value.getText()
                            .toString()
                            .trim()
            );
        } catch (Exception ignored) {
            return fallback;
        }
    }

    public static JSONObject obj(Object... values) {
        JSONObject object = new JSONObject();

        try {
            for (int i = 0; i < values.length; i += 2) {
                object.put(
                        String.valueOf(values[i]),
                        values[i + 1]
                );
            }
        } catch (Exception ignored) {
        }

        return object;
    }

}
