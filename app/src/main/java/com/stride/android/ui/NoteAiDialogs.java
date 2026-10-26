package com.stride.android.ui;

import android.app.Dialog;
import android.graphics.Color;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import androidx.activity.ComponentActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.viewbinding.ViewBinding;

/** Shared full-screen window behavior for binding-backed Notes dialogs. */
final class NoteAiDialogs {
    private NoteAiDialogs() { }
    static Dialog create(ComponentActivity activity, ViewBinding binding, Runnable dismissed) {
        Dialog dialog = new Dialog(activity, android.R.style.Theme_Material_Light_NoActionBar);
        dialog.setContentView(binding.getRoot());
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnDismissListener(value -> dismissed.run());
        Window window = dialog.getWindow();
        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, false);
            window.setStatusBarColor(Color.WHITE);
            window.setNavigationBarColor(Color.WHITE);
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            return insets;
        });
        return dialog;
    }
    static void show(Dialog dialog, ViewBinding binding) {
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            WindowCompat.getInsetsController(window, binding.getRoot()).setAppearanceLightStatusBars(true);
            WindowCompat.getInsetsController(window, binding.getRoot()).setAppearanceLightNavigationBars(true);
        }
        ViewCompat.requestApplyInsets(binding.getRoot());
    }
}
