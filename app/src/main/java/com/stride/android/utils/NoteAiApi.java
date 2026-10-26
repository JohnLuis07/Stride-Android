package com.stride.android.utils;

import android.content.Context;
import android.database.Cursor;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import com.stride.android.R;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Bounded PDF transfers; Gemini credentials and provider operations stay on the server. */
public final class NoteAiApi {
    public static final long MAX_BYTES = 10L * 1024L * 1024L;
    public interface FileResult { void done(File file, String error); }
    public interface Progress { void stage(int resource); }
    private final Context context;
    private final SupabaseApi api;
    private final ExecutorService transfers = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final java.util.Set<File> temporaryFiles = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    public NoteAiApi(Context context, SupabaseApi api) {
        this.context = context.getApplicationContext();
        this.api = api;
    }

    public void call(JSONObject body, SupabaseApi.JsonCallback callback) {
        if (!closed) api.invokeFunction("notes-ai", body, (value, error) -> {
            if (!closed) callback.done(value, error);
        });
    }

    public void upload(String noteId, Uri uri, Progress progress, SupabaseApi.JsonCallback callback) {
        int generation = api.sessionGeneration();
        transfers.execute(() -> {
            File file = null;
            try {
                check(generation);
                String name = "Document.pdf";
                try (Cursor cursor = context.getContentResolver().query(uri,
                        new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        if (column >= 0 && !cursor.isNull(column)) name = cursor.getString(column);
                        column = cursor.getColumnIndex(OpenableColumns.SIZE);
                        if (column >= 0 && !cursor.isNull(column) && cursor.getLong(column) > MAX_BYTES) {
                            throw new IllegalArgumentException("FILE_TOO_LARGE");
                        }
                    }
                }
                file = temporaryFile("note_ai_upload_");
                try (InputStream input = context.getContentResolver().openInputStream(uri);
                        OutputStream output = new FileOutputStream(file)) {
                    if (input == null) throw new IllegalArgumentException("INVALID_PDF");
                    copy(input, output, generation);
                }
                if (file.length() == 0) throw new IllegalArgumentException("INVALID_PDF");
                try (InputStream input = new FileInputStream(file)) {
                    byte[] header = new byte[1024];
                    int read = input.read(header);
                    if (read <= 0 || !new String(header, 0, read, StandardCharsets.ISO_8859_1).contains("%PDF-")) {
                        throw new IllegalArgumentException("INVALID_PDF");
                    }
                }
                int pages;
                try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
                        PdfRenderer renderer = new PdfRenderer(fd)) {
                    pages = renderer.getPageCount();
                    if (pages < 1 || pages > 1000) throw new IllegalArgumentException("INVALID_PDF");
                }
                File prepared = file;
                String title = name == null || name.trim().isEmpty() ? "Document.pdf" : name;
                main.post(() -> {
                    if (!valid(generation)) { discard(prepared); return; }
                    progress.stage(R.string.note_ai_uploading);
                    call(FeatureUi.obj("action", "begin_upload", "note_id", noteId,
                            "name", title.substring(0, Math.min(160, title.length())), "bytes", prepared.length(), "pages", pages),
                            (result, error) -> {
                                if (!valid(generation) || error != null || result == null) {
                                    discard(prepared);
                                    if (valid(generation)) callback.done(null, error == null ? "REQUEST_FAILED" : error);
                                    return;
                                }
                                transfers.execute(() -> sendFile(prepared, result, generation, progress, callback));
                            });
                });
                file = null; // Ownership transfers to the asynchronous upload.
            } catch (Exception error) {
                String code = error instanceof IllegalArgumentException ? error.getMessage() : "INVALID_PDF";
                main.post(() -> { if (valid(generation)) callback.done(null, code); });
            } finally {
                if (file != null) discard(file);
            }
        });
    }

    private void sendFile(File file, JSONObject result, int generation, Progress progress, SupabaseApi.JsonCallback callback) {
        HttpURLConnection connection = null;
        try {
            check(generation);
            String url = result.optString("upload_url");
            if (!SupabaseApi.isStorageUrl(url)) throw new IllegalArgumentException("REQUEST_FAILED");
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("PUT");
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(60000);
            connection.setRequestProperty("Content-Type", "application/pdf");
            connection.setFixedLengthStreamingMode(file.length());
            connection.setDoOutput(true);
            try (InputStream input = new FileInputStream(file); OutputStream output = connection.getOutputStream()) {
                copy(input, output, generation);
            }
            if (connection.getResponseCode() < 200 || connection.getResponseCode() >= 300) {
                throw new IllegalArgumentException("UPLOAD_MISSING");
            }
            String sourceId = result.getJSONObject("source").getString("id");
            main.post(() -> {
                if (!valid(generation)) return;
                progress.stage(R.string.note_ai_indexing);
                call(FeatureUi.obj("action", "index_source", "source_id", sourceId), callback);
            });
        } catch (Exception error) {
            main.post(() -> { if (valid(generation)) callback.done(null, "UPLOAD_MISSING"); });
        } finally {
            if (connection != null) connection.disconnect();
            discard(file);
        }
    }

    public void preview(String sourceId, FileResult callback) {
        int generation = api.sessionGeneration();
        call(FeatureUi.obj("action", "preview_source", "source_id", sourceId), (value, error) -> {
            if (error != null || value == null) { callback.done(null, error == null ? "PDF_UNAVAILABLE" : error); return; }
            transfers.execute(() -> {
                File file = null;
                HttpURLConnection connection = null;
                try {
                    check(generation);
                    String url = value.optString("url");
                    if (!SupabaseApi.isStorageUrl(url)) throw new IllegalArgumentException();
                    connection = (HttpURLConnection) new URL(url).openConnection();
                    connection.setInstanceFollowRedirects(false);
                    connection.setConnectTimeout(15000);
                    connection.setReadTimeout(30000);
                    if (connection.getResponseCode() != 200) throw new IllegalArgumentException();
                    file = temporaryFile("note_ai_preview_");
                    try (InputStream input = connection.getInputStream(); OutputStream output = new FileOutputStream(file)) {
                        copy(input, output, generation);
                    }
                    File downloaded = file;
                    main.post(() -> {
                        if (valid(generation)) {
                            temporaryFiles.remove(downloaded); // The native viewer now owns disposal.
                            callback.done(downloaded, null);
                        } else discard(downloaded);
                    });
                    file = null;
                } catch (Exception failure) {
                    main.post(() -> { if (valid(generation)) callback.done(null, "PDF_UNAVAILABLE"); });
                } finally {
                    if (connection != null) connection.disconnect();
                    if (file != null) discard(file);
                }
            });
        });
    }

    private void copy(InputStream input, OutputStream output, int generation) throws Exception {
        byte[] buffer = new byte[16384];
        long total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            check(generation);
            total += count;
            if (total > MAX_BYTES) throw new IllegalArgumentException("FILE_TOO_LARGE");
            output.write(buffer, 0, count);
        }
    }

    private boolean valid(int generation) { return !closed && generation == api.sessionGeneration() && api.isAuthenticated(); }
    private void check(int generation) {
        if (!valid(generation) || Thread.currentThread().isInterrupted()) throw new IllegalStateException("SESSION_EXPIRED");
    }

    public void close() {
        closed = true;
        transfers.shutdownNow();
        main.removeCallbacksAndMessages(null);
        for (File file : temporaryFiles) discard(file);
    }

    private File temporaryFile(String prefix) throws java.io.IOException {
        File file = File.createTempFile(prefix, ".pdf", context.getCacheDir());
        temporaryFiles.add(file);
        return file;
    }

    private void discard(File file) {
        if (file.delete() || !file.exists()) temporaryFiles.remove(file);
    }

    public static int errorResource(String error) {
        String code = error == null ? "REQUEST_FAILED" : error;
        try { code = new JSONObject(code).optString("error", code); } catch (Exception ignored) { }
        switch (code) {
            case "SESSION_EXPIRED": case SupabaseApi.SESSION_EXPIRED: return R.string.auth_session_expired;
            case "ACCOUNT_LINK_REQUIRED": return R.string.auth_migration_required;
            case "SETUP_REQUIRED": case "AI_SETUP_REQUIRED": return R.string.note_ai_setup_required;
            case "DAILY_LIMIT": case "AI_RATE_LIMIT": return R.string.note_ai_rate_limit;
            case "SOURCE_LIMIT": return R.string.note_ai_source_limit;
            case "SELECTION_LIMIT": return R.string.note_ai_selection_limit;
            case "FILE_TOO_LARGE": return R.string.note_ai_file_too_large;
            case "INVALID_PDF": return R.string.note_ai_invalid_pdf;
            case "UPLOAD_MISSING": return R.string.note_ai_upload_missing;
            case "SOURCE_NOT_READY": return R.string.note_ai_not_ready;
            case "NO_SOURCES": return R.string.note_ai_no_sources;
            case "REQUEST_BUSY": return R.string.note_ai_busy_elsewhere;
            case "SOURCE_NOT_FOUND": case "PDF_UNAVAILABLE": return R.string.note_ai_pdf_unavailable;
            default: return R.string.note_ai_request_failed;
        }
    }
}
