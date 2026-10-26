package com.stride.android.ui;

import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.ComponentActivity;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.stride.android.R;
import com.stride.android.databinding.DialogNoteAiPdfBinding;
import com.stride.android.databinding.ItemNoteAiPdfPageBinding;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Renders private cached PDFs locally. Pages are opened serially and bounded in memory. */
final class NoteAiPdfViewer {
    private final DialogNoteAiPdfBinding binding;
    private final Dialog dialog;
    private final File file;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<Bitmap> bitmaps = new HashSet<>();
    private PdfRenderer renderer;
    private volatile boolean closed;

    NoteAiPdfViewer(ComponentActivity activity, File file, String title, int page, Runnable dismissed) {
        this.file = file;
        binding = DialogNoteAiPdfBinding.inflate(activity.getLayoutInflater());
        binding.pdfTitle.setText(title);
        dialog = NoteAiDialogs.create(activity, binding, () -> { release(); dismissed.run(); });
        binding.closeButton.setOnClickListener(v -> dialog.dismiss());
        binding.pagesList.setLayoutManager(new LinearLayoutManager(activity));
        binding.pagesList.setItemAnimator(null);
        binding.pagesList.setItemViewCacheSize(0);
        worker.execute(() -> {
            try {
                ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
                try { renderer = new PdfRenderer(descriptor); } catch (Exception failure) { descriptor.close(); throw failure; }
                int count = renderer.getPageCount();
                if (count < 1 || count > 1000) throw new IllegalArgumentException();
                main.post(() -> {
                    if (closed) return;
                    binding.pagesList.setAdapter(new Pages(count));
                    binding.pagesList.scrollToPosition(Math.max(0, Math.min(count - 1, page - 1)));
                });
            } catch (Exception failure) {
                main.post(() -> { if (!closed) binding.pdfError.setVisibility(View.VISIBLE); });
            }
        });
    }
    void show() { NoteAiDialogs.show(dialog, binding); }
    void close() { dialog.dismiss(); }
    private void release() {
        if (closed) return;
        closed = true;
        binding.pagesList.setAdapter(null);
        for (Bitmap bitmap : bitmaps) if (!bitmap.isRecycled()) bitmap.recycle();
        bitmaps.clear();
        // Rendering and renderer disposal share one executor; no page can race with close().
        worker.execute(() -> { if (renderer != null) renderer.close(); file.delete(); });
        worker.shutdown();
    }

    private final class PageHolder extends RecyclerView.ViewHolder {
        final ItemNoteAiPdfPageBinding row;
        volatile int request;
        Bitmap bitmap;
        PageHolder(ItemNoteAiPdfPageBinding row) { super(row.getRoot()); this.row = row; }
        void clear() {
            request++;
            row.pageImage.setImageDrawable(null);
            if (bitmap != null) { bitmaps.remove(bitmap); if (!bitmap.isRecycled()) bitmap.recycle(); bitmap = null; }
        }
    }
    private final class Pages extends RecyclerView.Adapter<PageHolder> {
        private final int count;
        Pages(int count) { this.count = count; }
        @Override public int getItemCount() { return count; }
        @NonNull @Override public PageHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            return new PageHolder(ItemNoteAiPdfPageBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }
        @Override public void onBindViewHolder(@NonNull PageHolder holder, int position) {
            holder.clear();
            int request = holder.request;
            holder.row.pageLabel.setText(holder.itemView.getContext().getString(R.string.note_ai_pdf_page, position + 1));
            holder.row.pageImage.setContentDescription(holder.row.pageLabel.getText());
            worker.execute(() -> {
                if (closed || holder.request != request) return;
                Bitmap bitmap = null;
                try (PdfRenderer.Page page = renderer.openPage(position)) {
                    double scale = Math.min(1100.0 / Math.max(1, page.getWidth()),
                            Math.sqrt(1800000.0 / Math.max(1.0, (double) page.getWidth() * page.getHeight())));
                    bitmap = Bitmap.createBitmap(Math.max(1, (int) (page.getWidth() * scale)),
                            Math.max(1, (int) (page.getHeight() * scale)), Bitmap.Config.ARGB_8888);
                    bitmap.eraseColor(Color.WHITE);
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    Bitmap completed = bitmap;
                    main.post(() -> {
                        if (closed || holder.request != request) { completed.recycle(); return; }
                        holder.bitmap = completed;
                        bitmaps.add(completed);
                        int width = Math.max(1, binding.pagesList.getWidth() - holder.row.getRoot().getPaddingLeft()
                                - holder.row.getRoot().getPaddingRight());
                        ViewGroup.LayoutParams params = holder.row.pageImage.getLayoutParams();
                        params.height = (int) Math.min(100000, (double) width * completed.getHeight() / completed.getWidth());
                        holder.row.pageImage.setLayoutParams(params);
                        holder.row.pageImage.setImageBitmap(completed);
                    });
                } catch (Exception | OutOfMemoryError failure) {
                    if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
                    main.post(() -> { if (!closed) binding.pdfError.setVisibility(View.VISIBLE); });
                }
            });
        }
        @Override public void onViewRecycled(@NonNull PageHolder holder) { holder.clear(); }
    }
}
