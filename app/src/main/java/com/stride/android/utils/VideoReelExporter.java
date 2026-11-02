package com.stride.android.utils;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;

import androidx.annotation.NonNull;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.Presentation;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Downloads six owned clips, trims each to five seconds, and exports one silent portrait MP4. */
@androidx.annotation.OptIn(markerClass = UnstableApi.class)
public final class VideoReelExporter {
    public interface Callback {
        void progress(int downloaded, int exportPercent);
        void finished(File output, String error);
    }
    public interface SaveCallback { void finished(boolean success); }
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicInteger generation = new AtomicInteger();
    private volatile HttpURLConnection connection;
    private Transformer transformer;
    private Runnable progress;
    private boolean busy;

    public VideoReelExporter(Context context) { this.context = context.getApplicationContext(); }
    public boolean isBusy() { return busy; }

    public File output(String profile, String reel) {
        return new File(new File(context.getNoBackupFilesDir(), "video-reels/" + profile), reel + ".mp4");
    }

    public void export(String profile, String reel, List<String> links, Callback callback) {
        if (busy || links.size() != 6) return;
        busy = true;
        int request = generation.incrementAndGet();
        File destination = output(profile, reel);
        worker.execute(() -> {
            try {
                File folder = new File(context.getCacheDir(), "video-scenes/" + profile + "/" + reel);
                if (!folder.isDirectory() && !folder.mkdirs()) throw new IllegalStateException();
                if (!destination.getParentFile().isDirectory() && !destination.getParentFile().mkdirs()) throw new IllegalStateException();
                List<File> scenes = new ArrayList<>();
                for (int i = 0; i < 6; i++) {
                    check(request);
                    File file = new File(folder, "scene-" + i + ".mp4");
                    if (!validScene(file)) {
                        download(links.get(i), file, request);
                        if (!validScene(file)) { file.delete(); throw new IllegalStateException(); }
                    }
                    scenes.add(file);
                    int count = i + 1;
                    main.post(() -> { if (generation.get() == request) callback.progress(count, -1); });
                }
                main.post(() -> {
                    if (generation.get() == request) combine(scenes, destination, request, callback);
                });
            } catch (Exception failure) {
                main.post(() -> finish(request, destination, callback, false));
            }
        });
    }

    private boolean validScene(File file) {
        if (!file.isFile() || file.length() < 12 || file.length() > 41943040) return false;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            return Long.parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)) >= 5000;
        } catch (Exception ignored) { return false; }
        finally { try { retriever.release(); } catch (Exception ignored) { } }
    }

    private void download(String link, File output, int request) throws Exception {
        if (!SupabaseApi.isStorageUrl(link)) throw new IllegalArgumentException();
        File partial = new File(output.getPath() + ".part");
        HttpURLConnection current = (HttpURLConnection) new URL(link).openConnection();
        connection = current;
        current.setInstanceFollowRedirects(false);
        current.setConnectTimeout(15000);
        current.setReadTimeout(15000);
        long deadline = System.currentTimeMillis() + 120000;
        try {
            if (current.getResponseCode() != 200 || current.getContentLengthLong() > 41943040) throw new IllegalStateException();
            try (InputStream input = current.getInputStream(); OutputStream stream = new FileOutputStream(partial)) {
                byte[] buffer = new byte[32768];
                long size = 0;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    check(request);
                    size += count;
                    if (size > 41943040 || System.currentTimeMillis() > deadline) throw new IllegalStateException();
                    stream.write(buffer, 0, count);
                }
            }
            if (output.exists() && !output.delete()) throw new IllegalStateException();
            if (!partial.renameTo(output)) throw new IllegalStateException();
        } finally {
            current.disconnect();
            connection = null;
            partial.delete();
        }
    }

    private void combine(List<File> scenes, File destination, int request, Callback callback) {
        try {
            List<EditedMediaItem> items = new ArrayList<>();
            for (File scene : scenes) {
                MediaItem media = new MediaItem.Builder().setUri(Uri.fromFile(scene))
                        .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder().setEndPositionMs(5000).build()).build();
                items.add(new EditedMediaItem.Builder(media).setRemoveAudio(true)
                        .setEffects(new Effects(Collections.emptyList(), Collections.singletonList(
                                Presentation.createForWidthAndHeight(720, 1280, Presentation.LAYOUT_SCALE_TO_FIT)))).build());
            }
            Composition composition = new Composition.Builder(new EditedMediaItemSequence.Builder(items).build()).build();
            File partial = new File(destination.getPath() + ".partial.mp4");
            if (partial.exists() && !partial.delete()) throw new IllegalStateException();
            transformer = new Transformer.Builder(context).setVideoMimeType(MimeTypes.VIDEO_H264)
                    .addListener(new Transformer.Listener() {
                        @Override public void onCompleted(@NonNull Composition composition, @NonNull ExportResult result) {
                            if (generation.get() != request) return;
                            boolean moved = partial.isFile() && partial.length() > 12
                                    && result.durationMs >= 29900 && result.durationMs <= 30100
                                    && (!destination.exists() || destination.delete()) && partial.renameTo(destination);
                            if (!moved) partial.delete();
                            finish(request, destination, callback, moved);
                        }
                        @Override public void onError(@NonNull Composition composition, @NonNull ExportResult result,
                                @NonNull ExportException error) {
                            partial.delete();
                            finish(request, destination, callback, false);
                        }
                    }).build();
            long deadline = System.currentTimeMillis() + 600000;
            progress = new Runnable() {
                @Override public void run() {
                    if (generation.get() != request || transformer == null) return;
                    if (System.currentTimeMillis() > deadline) {
                        transformer.cancel(); partial.delete(); finish(request, destination, callback, false); return;
                    }
                    ProgressHolder holder = new ProgressHolder();
                    int state = transformer.getProgress(holder);
                    callback.progress(6, state == Transformer.PROGRESS_STATE_AVAILABLE ? holder.progress : -1);
                    main.postDelayed(this, 500);
                }
            };
            transformer.start(composition, partial.getAbsolutePath());
            main.post(progress);
        } catch (Exception failure) { finish(request, destination, callback, false); }
    }

    private void finish(int request, File output, Callback callback, boolean success) {
        if (generation.get() != request) return;
        if (progress != null) main.removeCallbacks(progress);
        transformer = null;
        busy = false;
        callback.finished(success ? output : null, success ? null : "VIDEO_EXPORT_ERROR");
    }

    public void save(File file, Uri destination, SaveCallback callback) {
        worker.execute(() -> {
            boolean success = false;
            try (InputStream input = new FileInputStream(file);
                 OutputStream output = context.getContentResolver().openOutputStream(destination, "wt")) {
                if (output == null) throw new IllegalStateException();
                byte[] buffer = new byte[32768];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                output.flush();
                success = true;
            } catch (Exception failure) {
                try { DocumentsContract.deleteDocument(context.getContentResolver(), destination); } catch (Exception ignored) { }
            }
            boolean saved = success;
            main.post(() -> callback.finished(saved));
        });
    }

    private void check(int request) throws InterruptedException {
        if (generation.get() != request || Thread.currentThread().isInterrupted()) throw new InterruptedException();
    }

    public void close() {
        generation.incrementAndGet();
        if (progress != null) main.removeCallbacks(progress);
        if (transformer != null) transformer.cancel();
        if (connection != null) connection.disconnect();
        transformer = null;
        busy = false;
        worker.shutdownNow();
    }
}
