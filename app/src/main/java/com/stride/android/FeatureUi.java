package com.stride.android;

import android.app.Activity;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import org.json.JSONObject;
import com.stride.android.databinding.DialogFormBinding;
import com.stride.android.databinding.ViewEntityRowBinding;
import com.stride.android.databinding.ViewTextInputBinding;
import com.stride.android.databinding.ViewTextBinding;

/** Reusable Android view helpers used by feature controllers. */
final class FeatureUi {
    static final int PRIMARY = 0xff5b5ce2;
    static final int INK = 0xff1d1b20;
    static final int MUTED = 0xff626370;
    private final Activity activity;

    FeatureUi(Activity activity) { this.activity = activity; }
    int dp(int value) { return (int) (value * activity.getResources().getDisplayMetrics().density + .5f); }
    void toast(String value) { Toast.makeText(activity, value, Toast.LENGTH_LONG).show(); }
    TextView text(String value, int size, int color) { ViewTextBinding binding=ViewTextBinding.inflate(activity.getLayoutInflater()); binding.textView.setText(value); binding.textView.setTextSize(size); binding.textView.setTextColor(color); return binding.textView; }
    LinearLayout dialogForm() { return DialogFormBinding.inflate(activity.getLayoutInflater()).dialogFormContainer; }
    EditText field(String hint, int type) { ViewTextInputBinding binding=ViewTextInputBinding.inflate(activity.getLayoutInflater()); binding.textInput.setHint(hint); binding.textInput.setInputType(type); return binding.textInput; }
    void row(LinearLayout parent, String title, String subtitle, View.OnClickListener click) { ViewEntityRowBinding binding=ViewEntityRowBinding.inflate(activity.getLayoutInflater(),parent,false); binding.entityTitle.setText(title); binding.entitySubtitle.setText(subtitle); binding.entitySubtitle.setVisibility(subtitle.isEmpty()?View.GONE:View.VISIBLE); binding.entityRow.setOnClickListener(click); parent.addView(binding.getRoot()); }
    static String now() { return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(new Date()); }
    static String today() { return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()); }
    static String strip(String value) { return value.replaceAll("<[^>]*>", "").replace("&nbsp;", " "); }
    static Object emptyNull(EditText value) { String text = value.getText().toString().trim(); return text.isEmpty() ? JSONObject.NULL : text; }
    static double number(EditText value, double fallback) { try { return Double.parseDouble(value.getText().toString().trim()); } catch (Exception ignored) { return fallback; } }
    static JSONObject obj(Object... values) { JSONObject object = new JSONObject(); try { for (int i = 0; i < values.length; i += 2) object.put(String.valueOf(values[i]), values[i + 1]); } catch (Exception ignored) { } return object; }
    static int emailInputType() { return InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS; }
}
