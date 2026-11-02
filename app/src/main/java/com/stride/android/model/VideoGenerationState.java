package com.stride.android.model;

import org.json.JSONArray;
import android.graphics.Bitmap;
import java.io.File;

/** Snapshot of server jobs and local submission state. */
public final class VideoGenerationState {
    public final JSONArray clips;
    public final boolean busy;
    public final boolean active;
    public final String error;
    public final int completed;
    public final boolean hasReel;
    public final boolean exporting;
    public final boolean saving;
    public final int downloaded;
    public final int exportPercent;
    public final File output;
    public final Bitmap preview;
    public final String prompt;
    public final boolean imageGenerating;
    public final boolean generatedImage;

    public VideoGenerationState(JSONArray clips, boolean busy, boolean active, String error, int completed,
            boolean hasReel, boolean exporting, boolean saving, int downloaded, int exportPercent,
            File output, Bitmap preview, String prompt, boolean imageGenerating, boolean generatedImage) {
        this.clips = clips;
        this.busy = busy;
        this.active = active;
        this.error = error;
        this.completed = completed;
        this.hasReel = hasReel;
        this.exporting = exporting;
        this.saving = saving;
        this.downloaded = downloaded;
        this.exportPercent = exportPercent;
        this.output = output;
        this.preview = preview;
        this.prompt = prompt;
        this.imageGenerating = imageGenerating;
        this.generatedImage = generatedImage;
    }
}
