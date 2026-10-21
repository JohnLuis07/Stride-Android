package com.stride.android.ui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;

import androidx.activity.ComponentActivity;
import androidx.annotation.NonNull;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.viewpager2.widget.ViewPager2;

import com.stride.android.R;
import com.stride.android.adapter.FeatureCarouselAdapter;
import com.stride.android.databinding.ScreenDashboardBinding;
import com.stride.android.model.FeatureBanner;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Binding owner for the workspace overview, rotating features, and tool shortcuts. */
public final class DashboardScreen implements FeatureScreen, LifecycleEventObserver {
    private static final long ROTATION_INTERVAL_MS = 10_000L;

    private final ComponentActivity activity;
    private final ScreenDashboardBinding binding;
    private final JSONObject user;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<FeatureBanner> banners = new ArrayList<>();
    private final Runnable rotate = this::advanceCarousel;
    private final AccessibilityManager accessibility;
    private final AccessibilityManager.TouchExplorationStateChangeListener explorationListener =
            enabled -> scheduleRotation();
    private boolean paused;
    private boolean closed;
    private int scrollState = ViewPager2.SCROLL_STATE_IDLE;

    private final ViewPager2.OnPageChangeCallback pageCallback =
            new ViewPager2.OnPageChangeCallback() {
                @Override
                public void onPageSelected(int position) {
                    showPageIndicator(position);
                }

                @Override
                public void onPageScrollStateChanged(int state) {
                    scrollState = state;
                    scheduleRotation();
                }
            };

    private final View.OnAttachStateChangeListener attachmentListener =
            new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View view) {
                    scheduleRotation();
                }

                @Override
                public void onViewDetachedFromWindow(View view) {
                    handler.removeCallbacks(rotate);
                }
            };

    public DashboardScreen(ComponentActivity activity, JSONObject user) {
        this.activity = activity;
        binding = ScreenDashboardBinding.inflate(activity.getLayoutInflater());
        this.user = user;
        accessibility = (AccessibilityManager) activity.getSystemService(Context.ACCESSIBILITY_SERVICE);
    }

    @Override
    public View root() {
        return binding.getRoot();
    }

    public void bind(Runnable notes, Runnable calendar, Runnable tasks,
            Runnable goals, Runnable focus) {
        String name = user.optString("full_name").trim();
        binding.pageTitle.setText(name.isEmpty()
                ? activity.getString(R.string.workspace_welcome_default)
                : activity.getString(R.string.workspace_welcome, name.split("\\s+")[0]));
        Locale locale = Locale.getDefault();
        String pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "EEE, MMM d");
        binding.dashboardDate.setText(new SimpleDateFormat(pattern, locale).format(new Date()));

        String features = user.optString("features", "");
        bindTool(binding.tasksCard, visible(features, "Todo"), tasks,
                R.string.workspace_open_tasks, R.string.workspace_tasks_description);
        bindTool(binding.calendarCard, visible(features, "Calendar"), calendar,
                R.string.workspace_open_calendar, R.string.workspace_calendar_description);
        bindTool(binding.notesCard, visible(features, "Notes"), notes,
                R.string.workspace_open_notes, R.string.workspace_notes_description);
        bindTool(binding.goalsCard, visible(features, "Goals"), goals,
                R.string.workspace_open_goals, R.string.workspace_goals_description);
        bindTool(binding.focusCard, visible(features, "Focus"), focus,
                R.string.workspace_open_focus, R.string.workspace_focus_description);

        addBanner(features, new FeatureBanner("Focus", R.string.workspace_focus_eyebrow,
                R.string.workspace_focus_title, R.string.workspace_focus_description,
                R.string.workspace_open_focus, R.drawable.ic_workspace_focus,
                R.drawable.bg_workspace_focus));
        addBanner(features, new FeatureBanner("Todo", R.string.carousel_tasks_label,
                R.string.carousel_tasks_title, R.string.carousel_tasks_description,
                R.string.workspace_open_tasks, R.drawable.ic_workspace_tasks,
                R.drawable.bg_carousel_tasks));
        addBanner(features, new FeatureBanner("Calendar", R.string.carousel_calendar_label,
                R.string.carousel_calendar_title, R.string.carousel_calendar_description,
                R.string.workspace_open_calendar, R.drawable.ic_workspace_calendar,
                R.drawable.bg_carousel_calendar));
        addBanner(features, new FeatureBanner("Notes", R.string.carousel_notes_label,
                R.string.carousel_notes_title, R.string.carousel_notes_description,
                R.string.workspace_open_notes, R.drawable.ic_workspace_notes,
                R.drawable.bg_carousel_notes));
        addBanner(features, new FeatureBanner("Goals", R.string.carousel_goals_label,
                R.string.carousel_goals_title, R.string.carousel_goals_description,
                R.string.workspace_open_goals, R.drawable.ic_workspace_goals,
                R.drawable.bg_carousel_goals));

        binding.featureCarousel.setAdapter(new FeatureCarouselAdapter(banners, banner -> {
            switch (banner.feature) {
                case "Todo":
                    tasks.run();
                    break;
                case "Calendar":
                    calendar.run();
                    break;
                case "Notes":
                    notes.run();
                    break;
                case "Goals":
                    goals.run();
                    break;
                case "Focus":
                    focus.run();
                    break;
            }
        }));
        // ViewPager2 needs an explicit height; grow it with the device's text scale.
        ViewGroup.LayoutParams pagerParams = binding.featureCarousel.getLayoutParams();
        float fontScale = activity.getResources().getConfiguration().fontScale;
        pagerParams.height = Math.round(activity.getResources().getDimensionPixelSize(
                R.dimen.workspace_carousel_height) * Math.max(1f, fontScale));
        binding.featureCarousel.setLayoutParams(pagerParams);
        binding.featureCarousel.registerOnPageChangeCallback(pageCallback);
        binding.carouselPlayback.setOnClickListener(v -> {
            paused = !paused;
            updatePlaybackControl();
            scheduleRotation();
        });
        binding.carouselControls.setVisibility(banners.size() > 1 ? View.VISIBLE : View.GONE);
        showPageIndicator(0);
        updatePlaybackControl();
        binding.getRoot().addOnAttachStateChangeListener(attachmentListener);
        activity.getLifecycle().addObserver(this);
        if (accessibility != null) {
            accessibility.addTouchExplorationStateChangeListener(explorationListener);
        }
        scheduleRotation();
    }

    private void addBanner(String features, FeatureBanner banner) {
        if ("Focus".equals(banner.feature) || visible(features, banner.feature)) {
            banners.add(banner);
        }
    }

    private void showPageIndicator(int position) {
        if (closed || position < 0 || position >= banners.size()) {
            return;
        }
        FeatureBanner banner = banners.get(position);
        binding.featureCarouselCard.setBackgroundResource(banner.background);
        View[] dots = {binding.carouselDotOne, binding.carouselDotTwo, binding.carouselDotThree,
                binding.carouselDotFour, binding.carouselDotFive};
        float density = activity.getResources().getDisplayMetrics().density;
        for (int i = 0; i < dots.length; i++) {
            dots[i].setVisibility(i < banners.size() ? View.VISIBLE : View.GONE);
            dots[i].setSelected(i == position);
            ViewGroup.LayoutParams params = dots[i].getLayoutParams();
            params.width = Math.round((i == position ? 18 : 6) * density);
            dots[i].setLayoutParams(params);
        }
        binding.carouselPosition.setText(activity.getString(
                R.string.carousel_position, position + 1, banners.size()));
        binding.carouselPosition.setContentDescription(activity.getString(
                R.string.carousel_position_description, activity.getString(banner.label),
                position + 1, banners.size()));
    }

    private void updatePlaybackControl() {
        binding.carouselPlayback.setText(paused ? R.string.carousel_resume : R.string.carousel_pause);
        binding.carouselPlayback.setContentDescription(activity.getString(paused
                ? R.string.carousel_resume_description : R.string.carousel_pause_description));
        binding.carouselPlayback.setCompoundDrawablesRelativeWithIntrinsicBounds(
                paused ? R.drawable.ic_carousel_play : R.drawable.ic_carousel_pause, 0, 0, 0);
    }

    private boolean canRotate() {
        return !closed && !paused && banners.size() > 1
                && scrollState == ViewPager2.SCROLL_STATE_IDLE
                && binding.getRoot().isAttachedToWindow()
                && binding.getRoot().isShown()
                && activity.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)
                && (accessibility == null || !accessibility.isTouchExplorationEnabled());
    }

    private void scheduleRotation() {
        handler.removeCallbacks(rotate);
        if (canRotate()) {
            handler.postDelayed(rotate, ROTATION_INTERVAL_MS);
        }
    }

    private void advanceCarousel() {
        if (canRotate()) {
            int nextPage = (binding.featureCarousel.getCurrentItem() + 1) % banners.size();
            binding.featureCarousel.setCurrentItem(nextPage, true);
        }
        scheduleRotation();
    }

    @Override
    public void onStateChanged(@NonNull LifecycleOwner source, @NonNull Lifecycle.Event event) {
        if (event == Lifecycle.Event.ON_RESUME) {
            scheduleRotation();
        } else if (event == Lifecycle.Event.ON_PAUSE) {
            handler.removeCallbacks(rotate);
        } else if (event == Lifecycle.Event.ON_DESTROY) {
            close();
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        handler.removeCallbacks(rotate);
        binding.featureCarousel.unregisterOnPageChangeCallback(pageCallback);
        binding.featureCarousel.setAdapter(null);
        binding.getRoot().removeOnAttachStateChangeListener(attachmentListener);
        activity.getLifecycle().removeObserver(this);
        if (accessibility != null) {
            accessibility.removeTouchExplorationStateChangeListener(explorationListener);
        }
    }

    private void bindTool(View card, boolean visible, Runnable open,
            int actionLabel, int description) {
        card.setVisibility(visible ? View.VISIBLE : View.GONE);
        card.setContentDescription(activity.getString(R.string.workspace_tool_accessibility,
                activity.getString(actionLabel), activity.getString(description)));
        card.setOnClickListener(v -> open.run());
    }

    private boolean visible(String features, String feature) {
        return features.isEmpty() || features.contains(feature);
    }
}
