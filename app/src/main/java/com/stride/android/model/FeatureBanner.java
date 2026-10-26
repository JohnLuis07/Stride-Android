package com.stride.android.model;

import androidx.annotation.DrawableRes;
import androidx.annotation.StringRes;

/** Immutable presentation data for a dashboard feature slide. */
public final class FeatureBanner {
    public final String feature;
    @StringRes public final int label;
    @StringRes public final int title;
    @StringRes public final int description;
    @StringRes public final int actionLabel;
    @DrawableRes public final int icon;
    @DrawableRes public final int background;

    public FeatureBanner(String feature, @StringRes int label, @StringRes int title,
            @StringRes int description, @StringRes int actionLabel,
            @DrawableRes int icon, @DrawableRes int background) {
        this.feature = feature;
        this.label = label;
        this.title = title;
        this.description = description;
        this.actionLabel = actionLabel;
        this.icon = icon;
        this.background = background;
    }
}
