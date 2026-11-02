package com.stride.android.ui;

import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.MediaController;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.stride.android.R;
import com.stride.android.adapter.VideoClipAdapter;
import com.stride.android.databinding.ScreenVideoGenerationBinding;
import com.stride.android.model.VideoGenerationState;
import com.stride.android.utils.SupabaseApi;
import com.stride.android.utils.VideoGenerationApi;
import com.stride.android.viewmodel.VideoGenerationViewModel;

/** Binding-backed studio for six scenes and a thirty-second local reel. */
public final class VideoGenerationScreen implements FeatureScreen, DefaultLifecycleObserver {
    private final ComponentActivity activity;
    private final ScreenVideoGenerationBinding binding;
    private final VideoGenerationViewModel model;
    private final VideoClipAdapter adapter;
    private final ActivityResultLauncher<String> imagePicker;
    private final ActivityResultLauncher<String> savePicker;
    private final Observer<VideoGenerationState> observer = this::render;
    private boolean closed;
    private boolean opening;
    private AlertDialog player;
    private final TextWatcher draftWatcher = new TextWatcher() {
        @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
        @Override public void onTextChanged(CharSequence text, int start, int before, int count) { model.draft = text.toString(); }
        @Override public void afterTextChanged(Editable text) { }
    };

    public VideoGenerationScreen(ComponentActivity activity, SupabaseApi api, String profile) {
        this.activity = activity;
        binding = ScreenVideoGenerationBinding.inflate(activity.getLayoutInflater());
        model = new ViewModelProvider(activity).get("video:" + profile, VideoGenerationViewModel.class);
        model.configure(api, profile);
        adapter = new VideoClipAdapter(this::openClip);
        imagePicker = activity.getActivityResultRegistry().register("video:image:" + profile,
                new ActivityResultContracts.GetContent(), uri -> {
                    if (!closed && uri != null) model.selectImage(uri);
                });
        savePicker = activity.getActivityResultRegistry().register("video:save:" + profile,
                new ActivityResultContracts.CreateDocument("video/mp4"), uri -> {
                    if (!closed && uri != null) model.save(uri);
                });
    }

    @Override public View root() { return binding.getRoot(); }

    public void bind() {
        binding.clipsList.setLayoutManager(new LinearLayoutManager(activity));
        binding.clipsList.setAdapter(adapter);
        binding.clipsList.setItemAnimator(null);
        binding.promptInput.setText(model.draft);
        binding.promptInput.addTextChangedListener(draftWatcher);
        binding.chooseImage.setOnClickListener(view -> imagePicker.launch("image/*"));
        binding.clearImage.setOnClickListener(view -> model.clearImage());
        binding.generate.setOnClickListener(view -> model.generate(model.selectedImage,
                binding.promptInput.getText().toString()));
        binding.refresh.setOnClickListener(view -> model.refresh());
        binding.newReel.setOnClickListener(view -> model.newReel());
        binding.exportReel.setOnClickListener(view -> model.exportReel());
        binding.playReel.setOnClickListener(view -> {
            VideoGenerationState state = model.state().getValue();
            if (state != null && state.output != null) play(Uri.fromFile(state.output));
        });
        binding.saveReel.setOnClickListener(view -> {
            VideoGenerationState state = model.state().getValue();
            if (state != null && state.output != null) savePicker.launch("Stride-" + state.output.getName());
        });
        model.state().observe(activity, observer);
        activity.getLifecycle().addObserver(this);
    }

    private void render(VideoGenerationState state) {
        if (closed || state == null) return;
        adapter.submit(state.clips);
        boolean working = state.busy || state.active || state.exporting || state.saving;
        binding.empty.setVisibility(state.clips.length() == 0 ? View.VISIBLE : View.GONE);
        binding.generate.setEnabled(!working);
        binding.generate.setText(state.hasReel ? R.string.video_continue : R.string.video_generate);
        binding.generate.setVisibility(state.completed == 6 ? View.GONE : View.VISIBLE);
        binding.chooseImage.setEnabled(!working);
        binding.chooseImage.setVisibility(state.hasReel ? View.GONE : View.VISIBLE);
        binding.chooseImage.setText(model.selectedImage == null ? R.string.video_choose : R.string.video_selected);
        binding.clearImage.setVisibility(!state.hasReel && model.selectedImage != null ? View.VISIBLE : View.GONE);
        binding.clearImage.setEnabled(!working);
        binding.promptInput.setEnabled(!working && !state.hasReel);
        if (!binding.promptInput.getText().toString().equals(state.prompt)) binding.promptInput.setText(state.prompt);
        binding.imagePreview.setImageBitmap(state.preview);
        binding.imagePreview.setVisibility(state.preview == null ? View.GONE : View.VISIBLE);
        binding.imagePlaceholder.setVisibility(state.preview == null ? View.VISIBLE : View.GONE);
        binding.imageHint.setText(state.imageGenerating ? R.string.video_generating_image
                : state.hasReel ? state.generatedImage ? R.string.video_generated_image : R.string.video_saved_image
                : R.string.video_image_hint);
        binding.sceneCount.setText(activity.getString(R.string.video_scene_count, state.completed));
        binding.sceneProgress.setProgress(state.completed);
        binding.sceneProgress.setContentDescription(activity.getString(R.string.video_scene_count, state.completed));
        binding.progress.setVisibility(working ? View.VISIBLE : View.GONE);
        binding.progress.setIndeterminate(!state.exporting || state.exportPercent < 0);
        if (state.exporting && state.exportPercent >= 0) binding.progress.setProgress(state.exportPercent);
        binding.newReel.setVisibility(state.hasReel ? View.VISIBLE : View.GONE);
        binding.newReel.setEnabled(!working);
        binding.reelActions.setVisibility(state.completed == 6 ? View.VISIBLE : View.GONE);
        binding.exportReel.setVisibility(state.output == null ? View.VISIBLE : View.GONE);
        binding.exportReel.setEnabled(!working);
        binding.playReel.setVisibility(state.output == null ? View.GONE : View.VISIBLE);
        binding.saveReel.setVisibility(state.output == null ? View.GONE : View.VISIBLE);
        binding.saveReel.setEnabled(!working);
        if (state.error != null) {
            int message = "INPUT_REQUIRED".equals(state.error) ? R.string.video_input_required
                    : "IMAGE_ERROR".equals(state.error) ? R.string.video_image_error
                    : "REEL_SAVED".equals(state.error) ? R.string.video_reel_saved : VideoGenerationApi.message(state.error);
            binding.message.setText(message);
        } else if (state.saving) binding.message.setText(R.string.video_saving_reel);
        else if (state.exporting) binding.message.setText(state.downloaded < 6
                ? activity.getString(R.string.video_download_progress, state.downloaded)
                : state.exportPercent < 0 ? activity.getString(R.string.video_combining)
                : activity.getString(R.string.video_export_progress, state.exportPercent));
        else if (state.imageGenerating) binding.message.setText(R.string.video_generating_image);
        else if (state.busy) binding.message.setText(R.string.video_busy);
        else if (state.active) binding.message.setText(R.string.video_queued);
        else binding.message.setText("");
    }

    private void openClip(String id, boolean download) {
        if (closed || opening) return;
        opening = true;
        model.link(id, (data, error) -> {
            opening = false;
            if (closed) return;
            if (error != null || data == null) { toast(VideoGenerationApi.message(error)); return; }
            Uri uri = Uri.parse(data.optString("url"));
            if (!SupabaseApi.isStorageUrl(uri.toString())) { toast(R.string.video_play_error); return; }
            if (download) download(id, uri); else play(uri);
        });
    }

    private void download(String id, Uri uri) {
        try {
            DownloadManager.Request request = new DownloadManager.Request(uri)
                    .setTitle("Stride-" + id + ".mp4").setMimeType("video/mp4")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            if (Build.VERSION.SDK_INT >= 29) request.setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS, "Stride-" + id + ".mp4");
            DownloadManager manager = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
            if (manager == null) throw new IllegalStateException();
            manager.enqueue(request);
            toast(R.string.video_download_started);
        } catch (Exception ignored) { toast(R.string.video_download_error); }
    }

    private void play(Uri uri) {
        if (player != null) player.dismiss();
        VideoView video = new VideoView(activity);
        MediaController controls = new MediaController(activity);
        controls.setAnchorView(video);
        video.setMediaController(controls);
        player = new AlertDialog.Builder(activity).setView(video).setPositiveButton(android.R.string.ok, null).create();
        player.setOnDismissListener(dialog -> video.stopPlayback());
        video.setOnPreparedListener(media -> video.start());
        video.setOnErrorListener((media, what, extra) -> { toast(R.string.video_play_error); return true; });
        player.show();
        video.setVideoURI(uri);
    }

    private void toast(int message) { Toast.makeText(activity, message, Toast.LENGTH_LONG).show(); }
    @Override public void onResume(@NonNull LifecycleOwner owner) { model.resume(); }
    @Override public void onPause(@NonNull LifecycleOwner owner) {
        model.pause();
        if (player != null) player.dismiss();
    }
    @Override public void onDestroy(@NonNull LifecycleOwner owner) { close(); }
    @Override public void close() {
        if (closed) return;
        closed = true;
        model.pause();
        model.state().removeObserver(observer);
        binding.promptInput.removeTextChangedListener(draftWatcher);
        activity.getLifecycle().removeObserver(this);
        imagePicker.unregister();
        savePicker.unregister();
        binding.clipsList.setAdapter(null);
        if (player != null) { player.dismiss(); player = null; }
    }
}
