package com.stride.android;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import org.json.JSONObject;

/** Reusable Android view helpers used by feature controllers. */
final class FeatureUi {
    static final int PRIMARY = Color.rgb(91, 92, 226);
    static final int INK = Color.rgb(29, 27, 32);
    static final int MUTED = Color.rgb(98, 99, 112);
    private final Activity activity;

    FeatureUi(Activity activity) { this.activity = activity; }
    int dp(int value) { return (int) (value * activity.getResources().getDisplayMetrics().density + .5f); }
    void toast(String value) { Toast.makeText(activity, value, Toast.LENGTH_LONG).show(); }
    TextView text(String value, int size, int color) { TextView view = new TextView(activity); view.setText(value); view.setTextSize(size); view.setTextColor(color); return view; }
    void spacer(LinearLayout parent, int value) { Space view = new Space(activity); parent.addView(view, new LinearLayout.LayoutParams(1, dp(value))); }
    LinearLayout dialogForm() { LinearLayout form = new LinearLayout(activity); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(dp(18), dp(6), dp(18), 0); return form; }
    EditText field(String hint, int type) { EditText input = new EditText(activity); input.setHint(hint); input.setTextSize(16); input.setTextColor(INK); input.setInputType(type); input.setPadding(dp(12), dp(10), dp(12), dp(10)); input.setBackground(round(Color.WHITE, 12)); LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2); layout.setMargins(0, 0, 0, dp(8)); input.setLayoutParams(layout); return input; }
    void row(LinearLayout parent, String title, String subtitle, View.OnClickListener click) { LinearLayout row = new LinearLayout(activity); row.setOrientation(LinearLayout.VERTICAL); row.setPadding(dp(16), dp(13), dp(16), dp(13)); row.setBackground(round(Color.WHITE, 16)); row.setOnClickListener(click); TextView heading = text(title, 18, INK); heading.setTypeface(null, Typeface.BOLD); row.addView(heading); if (!subtitle.isEmpty()) { spacer(row, 4); row.addView(text(subtitle, 14, MUTED)); } parent.addView(row); spacer(parent, 9); }
    private GradientDrawable round(int color, int radius) { GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(radius)); return drawable; }
    static String now() { return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(new Date()); }
    static String today() { return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()); }
    static String strip(String value) { return value.replaceAll("<[^>]*>", "").replace("&nbsp;", " "); }
    static Object emptyNull(EditText value) { String text = value.getText().toString().trim(); return text.isEmpty() ? JSONObject.NULL : text; }
    static double number(EditText value, double fallback) { try { return Double.parseDouble(value.getText().toString().trim()); } catch (Exception ignored) { return fallback; } }
    static JSONObject obj(Object... values) { JSONObject object = new JSONObject(); try { for (int i = 0; i < values.length; i += 2) object.put(String.valueOf(values[i]), values[i + 1]); } catch (Exception ignored) { } return object; }
    static int emailInputType() { return InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS; }
}
