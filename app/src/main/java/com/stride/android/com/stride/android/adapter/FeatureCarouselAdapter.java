package com.stride.android.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.stride.android.databinding.ItemFeatureBannerBinding;
import com.stride.android.model.FeatureBanner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Binding-backed feature slides; navigation is delegated to the dashboard. */
public final class FeatureCarouselAdapter
        extends RecyclerView.Adapter<FeatureCarouselAdapter.BannerHolder> {
    public interface OnFeatureClickListener {
        void onFeatureClick(FeatureBanner banner);
    }

    private final List<FeatureBanner> banners;
    private final OnFeatureClickListener listener;

    public FeatureCarouselAdapter(List<FeatureBanner> banners, OnFeatureClickListener listener) {
        this.banners = Collections.unmodifiableList(new ArrayList<>(banners));
        this.listener = listener;
    }

    @NonNull
    @Override
    public BannerHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new BannerHolder(ItemFeatureBannerBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull BannerHolder holder, int position) {
        FeatureBanner banner = banners.get(position);
        holder.binding.getRoot().setBackgroundResource(banner.background);
        holder.binding.bannerLabel.setText(banner.label);
        holder.binding.bannerTitle.setText(banner.title);
        holder.binding.bannerDescription.setText(banner.description);
        holder.binding.bannerIcon.setImageResource(banner.icon);
        holder.binding.bannerAction.setText(banner.actionLabel);
        holder.binding.bannerAction.setOnClickListener(v -> listener.onFeatureClick(banner));
    }

    @Override
    public int getItemCount() {
        return banners.size();
    }

    static final class BannerHolder extends RecyclerView.ViewHolder {
        private final ItemFeatureBannerBinding binding;

        BannerHolder(ItemFeatureBannerBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
