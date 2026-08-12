package com.stride.android;

import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import com.stride.android.databinding.ScreenNotesBinding;

final class NotesScreen implements FeatureScreen {
    private final ScreenNotesBinding binding;
    NotesScreen(LayoutInflater inflater) { binding = ScreenNotesBinding.inflate(inflater); }
    public View root() { return binding.getRoot(); }
    public LinearLayout content() { return binding.contentContainer; }
}
