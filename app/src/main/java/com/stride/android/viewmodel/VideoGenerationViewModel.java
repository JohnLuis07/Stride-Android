package com.stride.android.viewmodel;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.stride.android.model.VideoGenerationState;
import com.stride.android.utils.SupabaseApi;
import com.stride.android.utils.VideoGenerationApi;
import com.stride.android.utils.VideoReelExporter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Six sequential provider jobs, recoverable server scenes, and one local thirty-second export. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class VideoGenerationViewModel extends AndroidViewModel {
    private final MutableLiveData<VideoGenerationState> state = new MutableLiveData<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService images = Executors.newSingleThreadExecutor();
    private final Runnable poll = this::refresh;
    private final VideoReelExporter exporter;
    private VideoGenerationApi api;
    private JSONArray clips = new JSONArray();
    private String profile;
    private String reelId;
    private String creationId;
    private String creationPrompt;
    private Uri creationImage;
    private String pendingScene;
    private int pendingIndex = -1;
    private boolean hasReel;
    private boolean inputReady;
    private String inputStatus = "waiting";
    private boolean generatedImage;
    private String previewRequested;
    private boolean newDraft;
    private boolean busy;
    private boolean loading;
    private boolean observing;
    private boolean cleared;
    private boolean autoRun;
    private boolean exporting;
    private boolean saving;
    private int downloaded;
    private int exportPercent = -1;
    private int exportVersion;
    private int version;
    private int failures;
    private File output;
    private Bitmap preview;
    public Uri selectedImage;
    public String draft = "";

    public VideoGenerationViewModel(@NonNull Application application) {
        super(application);
        exporter = new VideoReelExporter(application);
        publish(null);
    }

    public void configure(SupabaseApi client, String owner) {
        version++;
        if (exporting && !exporter.isBusy()) { exporting = false; exportVersion++; }
        busy = false;
        loading = false;
        failures = 0;
        if (preview == null) previewRequested = null;
        profile = owner;
        api = new VideoGenerationApi(client);
        publish(null);
    }

    public LiveData<VideoGenerationState> state() { return state; }

    public void resume() { observing = true; refresh(); }
    public void pause() { observing = false; main.removeCallbacks(poll); }

    private int completed() {
        int count = 0;
        for (int i = 0; i < clips.length(); i++) {
            if ("complete".equals(clips.optJSONObject(i).optString("status"))) count++;
        }
        return count;
    }

    private boolean active() {
        if (imageGenerating()) return true;
        for (int i = 0; i < clips.length(); i++) {
            String status = clips.optJSONObject(i).optString("status");
            if ("queued".equals(status) || "saving".equals(status)) return true;
        }
        return false;
    }

    private void publish(String error) {
        if (!cleared) state.setValue(new VideoGenerationState(clips, busy, active(), error,
                completed(), hasReel, exporting, saving, downloaded, exportPercent, output, preview, draft,
                imageGenerating(), generatedImage));
    }

    private boolean imageGenerating() { return hasReel && !inputReady && "generating".equals(inputStatus); }

    public void clearImage() {
        if (hasReel || busy || cleared) return;
        selectedImage = null;
        preview = null;
        publish(null);
    }

    public void selectImage(Uri image) {
        if (hasReel || busy || cleared) return;
        selectedImage = image;
        preview = null;
        int request = version;
        images.execute(() -> {
            Bitmap thumb = null;
            try { thumb = decode(image, 640); }
            catch (Exception | OutOfMemoryError ignored) { }
            Bitmap result = thumb;
            main.post(() -> {
                if (cleared || request != version || !image.equals(selectedImage)) return;
                preview = result;
                publish(result == null ? "IMAGE_ERROR" : null);
            });
        });
    }

    public void newReel() {
        if (busy || active() || exporting || saving) return;
        version++;
        loading = false;
        autoRun = false;
        hasReel = false;
        inputReady = false;
        inputStatus = "waiting";
        generatedImage = false;
        previewRequested = null;
        if (selectedImage == null) preview = null;
        reelId = null;
        creationId = null;
        pendingScene = null;
        pendingIndex = -1;
        output = null;
        clips = new JSONArray();
        newDraft = true;
        main.removeCallbacks(poll);
        publish(null);
    }

    public void refresh() {
        if (api == null || cleared || loading || busy || newDraft) return;
        main.removeCallbacks(poll);
        loading = true;
        int request = version;
        api.list(reelId, (data, error) -> {
            if (cleared || request != version) return;
            loading = false;
            if (error != null || data == null) {
                failures++;
                publish(error == null ? "VIDEO_PROVIDER_ERROR" : error);
                if (observing && failures < 3) main.postDelayed(poll, 15000);
                return;
            }
            failures = 0;
            JSONObject reel = data.optJSONObject("reel");
            if (reel != null) {
                reelId = reel.optString("id");
                hasReel = true;
                inputReady = reel.optBoolean("input_ready");
                inputStatus = reel.optString("input_status", inputReady ? "ready" : "waiting");
                generatedImage = "generated".equals(reel.optString("input_source"));
                draft = reel.optString("prompt");
                clips = timeline(data.optJSONArray("clips"));
                File existing = exporter.output(profile, reelId);
                if (completed() == 6 && existing.isFile() && existing.length() > 12) output = existing;
                if (inputReady && preview == null) requestPreview(reelId);
            }
            String problem = null;
            if (!inputReady && "failed".equals(inputStatus)) {
                autoRun = false;
                problem = reel == null ? "VIDEO_IMAGE_ERROR" : reel.optString("input_error", "VIDEO_IMAGE_ERROR");
            }
            for (int i = 0; i < clips.length(); i++) {
                JSONObject clip = clips.optJSONObject(i);
                if ("failed".equals(clip.optString("status"))) {
                    autoRun = false;
                    problem = clip.optString("error_code");
                }
            }
            publish(problem);
            if (!observing) return;
            if (active()) main.postDelayed(poll, 5000);
            else if (autoRun && inputReady && completed() < 6 && !busy) submitNext();
            else if (autoRun && completed() == 6 && !exporting && output == null) exportReel();
        });
    }

    private JSONArray timeline(JSONArray attempts) {
        JSONArray result = new JSONArray();
        for (int scene = 0; scene < 6; scene++) {
            JSONObject chosen = null;
            if (attempts != null) {
                for (int i = 0; i < attempts.length(); i++) {
                    JSONObject attempt = attempts.optJSONObject(i);
                    if (attempt == null || attempt.optInt("scene_index", -1) != scene) continue;
                    if (chosen == null) chosen = attempt;
                    if ("complete".equals(attempt.optString("status"))) { chosen = attempt; break; }
                }
            }
            if (chosen == null) {
                chosen = com.stride.android.utils.FeatureUi.obj("id", "scene:" + scene,
                        "scene_index", scene, "status", "waiting", "prompt", draft);
            }
            result.put(chosen);
        }
        return result;
    }

    public void generate(Uri image, String prompt) {
        if (busy || active() || exporting || cleared || api == null) return;
        version++;
        if (preview == null) previewRequested = null;
        loading = false;
        main.removeCallbacks(poll);
        autoRun = true;
        if (hasReel && !inputReady) {
            retryStartingImage();
            return;
        }
        if (hasReel && inputReady) {
            // Only a confirmed failure gets a new attempt ID. Ambiguous submissions reuse theirs.
            int next = completed();
            if (next < clips.length() && "failed".equals(clips.optJSONObject(next).optString("status"))) {
                pendingScene = null;
                pendingIndex = -1;
            }
            submitNext();
            return;
        }
        if (prompt.trim().isEmpty()) { autoRun = false; publish("INPUT_REQUIRED"); return; }
        String clean = prompt.trim();
        if (creationId == null || !clean.equals(creationPrompt) || !java.util.Objects.equals(image, creationImage)) {
            creationId = UUID.randomUUID().toString();
            creationPrompt = clean;
            creationImage = image;
        }
        String id = creationId;
        busy = true;
        int request = version;
        publish(null);
        images.execute(() -> {
            String encoded = null;
            try { if (image != null) encoded = encode(image); } catch (Exception | OutOfMemoryError ignored) { }
            String payload = encoded;
            main.post(() -> {
                if (cleared || request != version) return;
                if (image != null && payload == null) { busy = false; autoRun = false; publish("IMAGE_ERROR"); return; }
                api.start(id, clean, payload, (data, error) -> {
                    if (cleared || request != version) return;
                    busy = false;
                    if (error != null) { autoRun = false; publish(submissionError(error)); return; }
                    reelId = id;
                    hasReel = true;
                    inputStatus = data == null ? "waiting" : data.optString("input_status", "waiting");
                    inputReady = data != null && data.optBoolean("input_ready");
                    generatedImage = data != null && "generated".equals(data.optString("input_source"));
                    newDraft = false;
                    refresh();
                });
            });
        });
    }

    private void retryStartingImage() {
        busy = true;
        int request = version;
        publish(null);
        api.retryImage(reelId, (data, error) -> {
            if (cleared || request != version) return;
            busy = false;
            if (error != null) { autoRun = false; publish(submissionError(error)); return; }
            inputStatus = data == null ? "waiting" : data.optString("input_status", "waiting");
            inputReady = data != null && data.optBoolean("input_ready");
            generatedImage = true;
            refresh();
        });
    }

    private void requestPreview(String reel) {
        if (reel.equals(previewRequested)) return;
        previewRequested = reel;
        int request = version;
        api.inputLink(reel, (data, error) -> {
            if (cleared || request != version || error != null || data == null) return;
            String link = data.optString("url");
            if (!SupabaseApi.isStorageUrl(link)) return;
            images.execute(() -> {
                Bitmap bitmap = null;
                HttpURLConnection connection = null;
                try {
                    connection = (HttpURLConnection) new URL(link).openConnection();
                    connection.setInstanceFollowRedirects(false);
                    connection.setConnectTimeout(10000);
                    connection.setReadTimeout(10000);
                    if (connection.getResponseCode() != 200 || connection.getContentLengthLong() > 2097152) throw new IllegalStateException();
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    try (InputStream input = connection.getInputStream()) {
                        byte[] buffer = new byte[16384];
                        int count;
                        long deadline = System.currentTimeMillis() + 20000;
                        while ((count = input.read(buffer)) != -1) {
                            if (bytes.size() + count > 2097152 || System.currentTimeMillis() > deadline) throw new IllegalStateException();
                            bytes.write(buffer, 0, count);
                        }
                    }
                    byte[] raw = bytes.toByteArray();
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(raw, 0, raw.length, options);
                    options.inSampleSize = 1;
                    while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 640) options.inSampleSize *= 2;
                    options.inJustDecodeBounds = false;
                    bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.length, options);
                } catch (Exception | OutOfMemoryError ignored) { /* Preview failure does not fail generation. */ }
                finally { if (connection != null) connection.disconnect(); }
                Bitmap result = bitmap;
                main.post(() -> {
                    if (!cleared && request == version && reel.equals(reelId) && result != null) {
                        preview = result;
                        publish(state.getValue() == null ? null : state.getValue().error);
                    }
                });
            });
        });
    }

    private void submitNext() {
        if (busy || active() || !hasReel || !inputReady || !observing) return;
        int index = completed();
        if (index >= 6) { exportReel(); return; }
        if (pendingScene == null || pendingIndex != index) {
            pendingScene = UUID.randomUUID().toString();
            pendingIndex = index;
        }
        String id = pendingScene;
        busy = true;
        int request = version;
        publish(null);
        api.scene(reelId, id, index, (data, error) -> {
            if (cleared || request != version) return;
            busy = false;
            if (error != null) { autoRun = false; publish(submissionError(error)); return; }
            // Refresh the accepted server row. Never invent a generation percentage.
            refresh();
        });
    }

    public void exportReel() {
        if (completed() != 6 || exporting || busy || saving || cleared) return;
        autoRun = false;
        exporting = true;
        downloaded = 0;
        exportPercent = -1;
        publish(null);
        gatherLinks(0, new ArrayList<>(), ++exportVersion);
    }

    private String submissionError(String error) {
        return VideoGenerationApi.message(error) == com.stride.android.R.string.video_network_error
                ? "SUBMISSION_UNCONFIRMED" : error;
    }

    private void gatherLinks(int index, List<String> links, int token) {
        if (token != exportVersion || cleared) return;
        if (index == 6) {
            exporter.export(profile, reelId, links, new VideoReelExporter.Callback() {
                @Override public void progress(int count, int percent) {
                    downloaded = count; exportPercent = percent; publish(null);
                }
                @Override public void finished(File file, String error) {
                    exporting = false; output = file; publish(error);
                }
            });
            return;
        }
        api.link(clips.optJSONObject(index).optString("id"), (data, error) -> {
            if (cleared || token != exportVersion) return;
            if (error != null || data == null) { exporting = false; publish(error); return; }
            links.add(data.optString("url"));
            gatherLinks(index + 1, links, token);
        });
    }

    public void save(Uri destination) {
        if (output == null || saving) return;
        saving = true;
        publish(null);
        exporter.save(output, destination, success -> {
            if (cleared) return;
            saving = false;
            publish(success ? "REEL_SAVED" : "VIDEO_EXPORT_ERROR");
        });
    }

    private Bitmap decode(Uri uri, int maximum) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = getApplication().getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(input, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IllegalArgumentException();
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > maximum) options.inSampleSize *= 2;
        Bitmap bitmap;
        try (InputStream input = getApplication().getContentResolver().openInputStream(uri)) {
            bitmap = BitmapFactory.decodeStream(input, null, options);
        }
        if (bitmap == null) throw new IllegalArgumentException();
        try (InputStream input = getApplication().getContentResolver().openInputStream(uri)) {
            int orientation = new ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL);
            Matrix matrix = new Matrix();
            switch (orientation) {
                case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: matrix.setScale(-1, 1); break;
                case ExifInterface.ORIENTATION_ROTATE_180: matrix.setRotate(180); break;
                case ExifInterface.ORIENTATION_FLIP_VERTICAL: matrix.setScale(1, -1); break;
                case ExifInterface.ORIENTATION_TRANSPOSE: matrix.setRotate(90); matrix.postScale(-1, 1); break;
                case ExifInterface.ORIENTATION_ROTATE_90: matrix.setRotate(90); break;
                case ExifInterface.ORIENTATION_TRANSVERSE: matrix.setRotate(270); matrix.postScale(-1, 1); break;
                case ExifInterface.ORIENTATION_ROTATE_270: matrix.setRotate(270); break;
                default: break;
            }
            if (!matrix.isIdentity()) {
                Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                if (rotated != bitmap) { bitmap.recycle(); bitmap = rotated; }
            }
        } catch (Exception ignored) { /* Images without EXIF retain their decoded orientation. */ }
        return bitmap;
    }

    private String encode(Uri uri) throws Exception {
        Bitmap bitmap = decode(uri, 1024);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output);
            if (output.size() > 1048576) throw new IllegalArgumentException();
            return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP);
        } finally { bitmap.recycle(); }
    }

    public void link(String id, SupabaseApi.JsonCallback callback) { api.link(id, callback); }

    @Override protected void onCleared() {
        cleared = true;
        version++;
        pause();
        images.shutdownNow();
        exporter.close();
    }
}
