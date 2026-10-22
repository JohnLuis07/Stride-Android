package com.stride.android.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.PopupMenu;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.stride.android.R;
import com.stride.android.databinding.ItemNoteBinding;
import com.stride.android.model.Note;

import java.util.Date;

/** The only Notes adapter: each item represents one saved note. */
public final class NotesAdapter extends ListAdapter<Note, NotesAdapter.NoteHolder> {
    public interface Actions {
        void onOpen(Note note);
        void onDelete(Note note);
    }

    private static final DiffUtil.ItemCallback<Note> DIFF = new DiffUtil.ItemCallback<Note>() {
        @Override
        public boolean areItemsTheSame(@NonNull Note first, @NonNull Note second) {
            return first.id.equals(second.id);
        }

        @Override
        public boolean areContentsTheSame(@NonNull Note first, @NonNull Note second) {
            return first.title.equals(second.title) && first.content.equals(second.content)
                    && first.updatedAt == second.updatedAt;
        }
    };
    private final Actions actions;
    private boolean busy;

    public NotesAdapter(Actions actions) {
        super(DIFF);
        this.actions = actions;
    }

    public void setBusy(boolean busy) {
        if (this.busy != busy) {
            this.busy = busy;
            notifyItemRangeChanged(0, getItemCount());
        }
    }

    @NonNull
    @Override
    public NoteHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new NoteHolder(ItemNoteBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull NoteHolder holder, int position) {
        Note note = getItem(position);
        ItemNoteBinding binding = holder.binding;
        Context context = holder.itemView.getContext();
        String title = note.title.trim().isEmpty() ? context.getString(R.string.notes_untitled) : note.title;
        binding.noteTitle.setText(title);
        binding.notePreview.setText(note.preview.isEmpty() ? context.getString(
                note.hasImages ? R.string.notes_image_preview : R.string.notes_no_content) : note.preview);
        binding.noteUpdated.setText(note.updatedAt == 0 ? context.getString(R.string.notes_updated_recently)
                : context.getString(R.string.notes_updated,
                        android.text.format.DateFormat.getMediumDateFormat(context).format(new Date(note.updatedAt))));
        binding.getRoot().setEnabled(!busy);
        binding.getRoot().setFocusable(true);
        binding.getRoot().setContentDescription(context.getString(R.string.notes_open_description, title));
        binding.getRoot().setOnClickListener(v -> {
            if (!busy) {
                actions.onOpen(note);
            }
        });
        binding.actionsButton.setEnabled(!busy);
        binding.actionsButton.setContentDescription(context.getString(R.string.notes_actions_description, title));
        binding.actionsButton.setOnClickListener(v -> {
            if (busy) {
                return;
            }
            PopupMenu menu = new PopupMenu(context, binding.actionsButton);
            menu.getMenu().add(0, 1, 0, R.string.notes_edit);
            menu.getMenu().add(0, 2, 1, R.string.notes_delete);
            menu.setOnMenuItemClickListener(item -> {
                if (!busy) {
                    if (item.getItemId() == 1) {
                        actions.onOpen(note);
                    } else {
                        actions.onDelete(note);
                    }
                }
                return true;
            });
            menu.show();
        });
    }

    static final class NoteHolder extends RecyclerView.ViewHolder {
        final ItemNoteBinding binding;

        NoteHolder(ItemNoteBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
