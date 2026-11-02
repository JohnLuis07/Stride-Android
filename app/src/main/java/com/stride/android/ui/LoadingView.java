package com.stride.android.ui;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;

import com.stride.android.R;
import com.stride.android.databinding.ViewLoadingBinding;

/** Keeps an uninitialized screen hidden, while preserving content during later refreshes. */
public final class LoadingView {
    private final ViewLoadingBinding binding;
    private final View content;
    private boolean requestStarted;
    private boolean ready;

    public LoadingView(Activity activity, View content, Runnable retry) {
        binding = ViewLoadingBinding.inflate(activity.getLayoutInflater());
        this.content = content;
        if (content != null) {
            binding.getRoot().addView(content, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        binding.loadingRetry.setOnClickListener(view -> {
            if (retry != null) {
                showLoading(R.string.workspace_loading_title, R.string.workspace_loading_message);
                retry.run();
            }
        });
        showLoading(R.string.workspace_loading_title, R.string.workspace_loading_message);
    }

    public View root() {
        return binding.getRoot();
    }

    public void showLoading(int title, int message) {
        hideContent();
        binding.loadingTitle.setText(title);
        binding.loadingMessage.setText(message);
        binding.loadingSpinner.setVisibility(View.VISIBLE);
        binding.loadingRetry.setVisibility(View.GONE);
        binding.loadingPanel.setVisibility(View.VISIBLE);
    }

    public void showContent() {
        if (content != null) {
            content.setVisibility(View.VISIBLE);
            content.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        }
        binding.loadingPanel.setVisibility(View.GONE);
    }

    public void update(boolean loading, boolean failed) {
        // Ignore the ViewModel's default state before its first request starts.
        requestStarted |= loading;
        if (ready || !requestStarted) {
            return;
        }
        if (loading) {
            showLoading(R.string.workspace_loading_title, R.string.workspace_loading_message);
        } else if (failed) {
            hideContent();
            binding.loadingTitle.setText(R.string.workspace_loading_failed_title);
            binding.loadingMessage.setText(R.string.workspace_loading_failed_message);
            binding.loadingSpinner.setVisibility(View.GONE);
            binding.loadingRetry.setVisibility(View.VISIBLE);
            binding.loadingPanel.setVisibility(View.VISIBLE);
        } else {
            ready = true;
            showContent();
        }
    }

    private void hideContent() {
        if (content != null) {
            View focused = content.findFocus();
            if (focused != null && focused.getWindowToken() != null) {
                InputMethodManager keyboard = (InputMethodManager) content.getContext()
                        .getSystemService(Context.INPUT_METHOD_SERVICE);
                if (keyboard != null) {
                    keyboard.hideSoftInputFromWindow(focused.getWindowToken(), 0);
                }
                focused.clearFocus();
            }
            // INVISIBLE keeps measurements available to viewport-dependent RecyclerViews.
            content.setVisibility(View.INVISIBLE);
            content.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        }
    }
}
