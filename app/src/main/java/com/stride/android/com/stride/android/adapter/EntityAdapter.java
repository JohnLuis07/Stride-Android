package com.stride.android.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.stride.android.databinding.ViewEntityRowBinding;
import com.stride.android.model.EntityRow;

/** Shared, binding-backed rows for tasks, notes, goals, and calendar events. */
public final class EntityAdapter extends ListAdapter<EntityRow, EntityAdapter.RowHolder> {
    private static final DiffUtil.ItemCallback<EntityRow> DIFF =
            new DiffUtil.ItemCallback<EntityRow>() {
                @Override
                public boolean areItemsTheSame(@NonNull EntityRow oldRow, @NonNull EntityRow newRow) {
                    return oldRow.id.equals(newRow.id);
                }

                @Override
                public boolean areContentsTheSame(@NonNull EntityRow oldRow, @NonNull EntityRow newRow) {
                    return oldRow.title.equals(newRow.title)
                            && oldRow.subtitle.equals(newRow.subtitle);
                }
            };

    public EntityAdapter() {
        super(DIFF);
    }

    @NonNull
    @Override
    public RowHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new RowHolder(ViewEntityRowBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RowHolder holder, int position) {
        EntityRow row = getItem(position);
        holder.binding.entityTitle.setText(row.title);
        holder.binding.entitySubtitle.setText(row.subtitle);
        holder.binding.entitySubtitle.setVisibility(row.subtitle.isEmpty() ? View.GONE : View.VISIBLE);
    }

    static final class RowHolder extends RecyclerView.ViewHolder {
        private final ViewEntityRowBinding binding;

        RowHolder(ViewEntityRowBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
