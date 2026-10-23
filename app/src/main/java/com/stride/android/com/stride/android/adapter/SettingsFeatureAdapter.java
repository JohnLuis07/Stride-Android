package com.stride.android.adapter;

import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.stride.android.R;
import com.stride.android.databinding.ItemSettingsFeatureBinding;

import org.json.JSONArray;

/** Owns the temporary selection until dashboard preferences are saved. */
public final class SettingsFeatureAdapter extends RecyclerView.Adapter<SettingsFeatureAdapter.Holder> {
    private static final String[] KEYS = {"Notes", "Calendar", "Todo", "Goals", "Focus"};
    private static final int[] TITLES = {R.string.settings_notes, R.string.settings_calendar,
            R.string.settings_tasks, R.string.settings_goals, R.string.settings_focus};
    private static final int[] DESCRIPTIONS = {R.string.settings_notes_hint, R.string.settings_calendar_hint,
            R.string.settings_tasks_hint, R.string.settings_goals_hint, R.string.settings_focus_hint};
    private static final int[] ICONS = {R.drawable.ic_workspace_notes, R.drawable.ic_workspace_calendar,
            R.drawable.ic_workspace_tasks, R.drawable.ic_workspace_goals, R.drawable.ic_workspace_focus};
    private static final int[] BACKGROUNDS = {R.drawable.bg_workspace_notes_icon, R.drawable.bg_workspace_calendar_icon,
            R.drawable.bg_workspace_tasks_icon, R.drawable.bg_workspace_goals_icon, R.drawable.bg_workspace_avatar};
    private static final int[] COLORS = {R.color.workspace_notes, R.color.workspace_calendar,
            R.color.workspace_tasks, R.color.workspace_goals, R.color.workspace_accent};
    private final boolean[] selected = new boolean[KEYS.length];
    private final Runnable onSelectionChanged;
    private boolean enabled = true;

    public SettingsFeatureAdapter(Runnable onSelectionChanged) {
        this.onSelectionChanged = onSelectionChanged;
    }

    public void setSelection(JSONArray values) {
        for (int i = 0; i < KEYS.length; i++) {
            selected[i] = values == null;
            if (values != null) {
                for (int j = 0; j < values.length(); j++) {
                    if (KEYS[i].equals(values.optString(j))) {
                        selected[i] = true;
                        break;
                    }
                }
            }
        }
        notifyDataSetChanged();
    }

    public JSONArray selection() {
        JSONArray values = new JSONArray();
        for (int i = 0; i < KEYS.length; i++) {
            if (selected[i]) {
                values.put(KEYS[i]);
            }
        }
        return values;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(ItemSettingsFeatureBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        ItemSettingsFeatureBinding binding = holder.binding;
        String title = binding.getRoot().getContext().getString(TITLES[position]);
        String description = binding.getRoot().getContext().getString(DESCRIPTIONS[position]);
        SpannableString label = new SpannableString(title + "\n" + description);
        label.setSpan(new StyleSpan(Typeface.BOLD), 0, title.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        label.setSpan(new RelativeSizeSpan(0.9f), title.length() + 1, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        label.setSpan(new ForegroundColorSpan(binding.getRoot().getContext().getColor(R.color.workspace_secondary)),
                title.length() + 1, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        binding.featureIcon.setImageResource(ICONS[position]);
        binding.featureIcon.setBackgroundResource(BACKGROUNDS[position]);
        binding.featureIcon.setColorFilter(binding.getRoot().getContext().getColor(COLORS[position]));
        binding.featureToggle.setOnCheckedChangeListener(null);
        binding.featureToggle.setText(label);
        binding.featureToggle.setChecked(selected[position]);
        binding.featureToggle.setEnabled(enabled);
        binding.getRoot().setAlpha(enabled ? 1f : 0.6f);
        binding.featureToggle.setOnCheckedChangeListener((button, checked) -> {
            int index = holder.getBindingAdapterPosition();
            if (index != RecyclerView.NO_POSITION) {
                selected[index] = checked;
                onSelectionChanged.run();
            }
        });
    }

    @Override
    public int getItemCount() {
        return KEYS.length;
    }

    static final class Holder extends RecyclerView.ViewHolder {
        private final ItemSettingsFeatureBinding binding;

        Holder(ItemSettingsFeatureBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
