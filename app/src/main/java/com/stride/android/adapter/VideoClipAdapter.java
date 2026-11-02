package com.stride.android.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.stride.android.R;
import com.stride.android.databinding.ItemVideoClipBinding;
import com.stride.android.utils.VideoGenerationApi;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class VideoClipAdapter extends ListAdapter<JSONObject, VideoClipAdapter.Holder> {
    public interface Actions { void open(String id, boolean download); }
    private final Actions actions;

    public VideoClipAdapter(Actions actions) {
        super(new DiffUtil.ItemCallback<JSONObject>() {
            @Override public boolean areItemsTheSame(@NonNull JSONObject oldItem, @NonNull JSONObject newItem) {
                return oldItem.optInt("scene_index", -1) == newItem.optInt("scene_index", -1);
            }
            @Override public boolean areContentsTheSame(@NonNull JSONObject oldItem, @NonNull JSONObject newItem) {
                return oldItem.toString().equals(newItem.toString());
            }
        });
        this.actions = actions;
    }

    public void submit(JSONArray rows) {
        List<JSONObject> values = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row != null) values.add(row);
        }
        submitList(values);
    }

    @NonNull @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(ItemVideoClipBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        JSONObject clip = getItem(position);
        ItemVideoClipBinding binding = holder.binding;
        int scene = clip.optInt("scene_index", position) + 1;
        binding.prompt.setText(binding.getRoot().getContext().getString(R.string.video_scene_title, scene));
        binding.sceneNumber.setText(binding.getRoot().getContext().getString(R.string.video_scene_number, scene));
        String status = clip.optString("status");
        boolean complete = "complete".equals(status);
        int label = complete ? R.string.video_complete : "waiting".equals(status) ? R.string.video_waiting : "saving".equals(status) ? R.string.video_saving
                : "failed".equals(status) ? VideoGenerationApi.message(clip.optString("error_code")) : R.string.video_queued;
        binding.status.setText(label);
        binding.play.setVisibility(complete ? View.VISIBLE : View.GONE);
        binding.download.setVisibility(complete ? View.VISIBLE : View.GONE);
        binding.play.setOnClickListener(view -> actions.open(clip.optString("id"), false));
        binding.download.setOnClickListener(view -> actions.open(clip.optString("id"), true));
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final ItemVideoClipBinding binding;
        Holder(ItemVideoClipBinding binding) { super(binding.getRoot()); this.binding = binding; }
    }
}
