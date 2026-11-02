package com.stride.android.utils;

import androidx.annotation.StringRes;

import com.stride.android.R;

import org.json.JSONObject;

/** Small authenticated API facade. Provider credentials never enter the APK. */
public final class VideoGenerationApi {
    private final SupabaseApi api;

    public VideoGenerationApi(SupabaseApi api) {
        this.api = api;
    }

    public void list(String reel, SupabaseApi.JsonCallback callback) {
        api.invokeFunction("generate-video", FeatureUi.obj("action", "list", "reel_id", reel), callback);
    }

    public void start(String id, String prompt, String image, SupabaseApi.JsonCallback callback) {
        api.invokeFunction("generate-video", FeatureUi.obj("action", "create_reel", "id", id,
                "prompt", prompt, "image", image), callback);
    }

    public void scene(String reel, String id, int index, SupabaseApi.JsonCallback callback) {
        api.invokeFunction("generate-video", FeatureUi.obj("action", "scene", "id", id,
                "reel_id", reel, "scene_index", index), callback);
    }

    public void retryImage(String reel, SupabaseApi.JsonCallback callback) {
        api.invokeFunction("generate-video", FeatureUi.obj("action", "retry_image", "reel_id", reel), callback);
    }

    public void inputLink(String reel, SupabaseApi.JsonCallback callback) {
        api.invokeFunction("generate-video", FeatureUi.obj("action", "input_link", "reel_id", reel), callback);
    }

    public void link(String id, SupabaseApi.JsonCallback callback) {
        api.invokeFunction("generate-video", FeatureUi.obj("action", "link", "id", id), callback);
    }

    @StringRes
    public static int message(String error) {
        String code = error == null ? "" : error;
        try { code = new JSONObject(code).optString("error", code); }
        catch (Exception ignored) { /* Transport failures are plain text. */ }
        switch (code) {
            case "VIDEO_IMAGE_ERROR": return R.string.video_generated_image_error;
            case "VIDEO_IMAGE_LIMIT": return R.string.video_image_limit;
            case "VIDEO_EXPORT_ERROR": return R.string.video_export_error;
            case "VIDEO_REEL_LIMIT": return R.string.video_reel_limit;
            case "VIDEO_REFRESH_REQUIRED": return R.string.video_refresh_required;
            case "SUBMISSION_UNCONFIRMED": return R.string.video_retry_notice;
            case "VIDEO_ACCESS_DENIED":
            case "STRIDE_SESSION_EXPIRED": return R.string.video_access_error;
            case "VIDEO_SETUP_REQUIRED":
            case "VIDEO_STORAGE_ERROR": return R.string.video_setup_error;
            case "VIDEO_QUOTA": return R.string.video_quota_error;
            case "VIDEO_DAILY_LIMIT": return R.string.video_daily_error;
            case "VIDEO_TIMEOUT": return R.string.video_timeout_error;
            case "VIDEO_ALREADY_RUNNING": return R.string.video_running_error;
            case "VIDEO_PROVIDER_ERROR": return R.string.video_provider_error;
            default: return R.string.video_network_error;
        }
    }
}
