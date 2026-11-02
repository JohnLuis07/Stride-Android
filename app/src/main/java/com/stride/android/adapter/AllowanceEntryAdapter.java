package com.stride.android.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.stride.android.R;
import com.stride.android.databinding.ItemAllowanceEntryBinding;
import com.stride.android.model.AllowanceEntry;
import com.stride.android.utils.AllowanceMoney;
import com.stride.android.utils.CalendarDates;

/** Recycles transaction rows; owns no budget calculations or persistence. */
public final class AllowanceEntryAdapter extends ListAdapter<AllowanceEntry, AllowanceEntryAdapter.Holder> {
    public interface Actions { void edit(AllowanceEntry entry); void delete(AllowanceEntry entry); void confirm(AllowanceEntry entry); }
    private final Actions actions;
    private boolean enabled;
    public AllowanceEntryAdapter(Actions actions) {
        super(new DiffUtil.ItemCallback<AllowanceEntry>() {
            @Override public boolean areItemsTheSame(@NonNull AllowanceEntry first, @NonNull AllowanceEntry second) { return first.id.equals(second.id); }
            @Override public boolean areContentsTheSame(@NonNull AllowanceEntry first, @NonNull AllowanceEntry second) {
                return first.revision == second.revision && first.sameValues(second);
            }
        });
        this.actions = actions;
    }
    public void setEnabled(boolean value) {
        if (enabled != value) { enabled = value; notifyItemRangeChanged(0, getItemCount()); }
    }
    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(ItemAllowanceEntryBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }
    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        AllowanceEntry entry = getItem(position);
        ItemAllowanceEntryBinding binding = holder.binding;
        android.content.Context context = binding.getRoot().getContext();
        binding.category.setText(entry.category);
        binding.amount.setText(context.getString(entry.income() ? R.string.allowance_signed_income : R.string.allowance_signed_expense,
                AllowanceMoney.format(entry.amount, entry.currency)));
        binding.amount.setTextColor(context.getColor(entry.income() ? R.color.allowance_accent : R.color.allowance_expense));
        binding.metadata.setText(context.getString(R.string.allowance_entry_meta,
                context.getString(entry.income() ? R.string.allowance_income : R.string.allowance_expense), CalendarDates.label(entry.date, "EEE MMM d yyyy")));
        binding.note.setText(entry.note);
        binding.status.setText(entry.planned() ? R.string.allowance_planned : R.string.allowance_posted);
        binding.confirm.setVisibility(entry.planned() ? View.VISIBLE : View.GONE);
        binding.confirm.setEnabled(enabled);
        binding.confirm.setText(entry.income() ? entry.salary() ? R.string.allowance_salary_received : R.string.allowance_received : R.string.allowance_paid);
        binding.confirm.setContentDescription(context.getString(R.string.allowance_confirm_entry, entry.category));
        binding.confirm.setOnClickListener(view -> actions.confirm(entry));
        binding.note.setVisibility(entry.note.isEmpty() ? View.GONE : View.VISIBLE);
        binding.edit.setEnabled(enabled); binding.delete.setEnabled(enabled);
        binding.edit.setContentDescription(context.getString(R.string.allowance_edit_entry, entry.category));
        binding.delete.setContentDescription(context.getString(R.string.allowance_delete_entry, entry.category));
        binding.edit.setOnClickListener(view -> actions.edit(entry));
        binding.delete.setOnClickListener(view -> actions.delete(entry));
    }
    static final class Holder extends RecyclerView.ViewHolder {
        final ItemAllowanceEntryBinding binding;
        Holder(ItemAllowanceEntryBinding binding) { super(binding.getRoot()); this.binding = binding; }
    }
}
