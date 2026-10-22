package com.stride.android.ui;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.Color;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.activity.ComponentActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.Observer;

import com.stride.android.R;
import com.stride.android.databinding.DialogNoteEditorBinding;
import com.stride.android.model.NotesState;
import com.stride.android.viewmodel.NotesViewModel;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Full-screen, binding-backed editor with a local HTML surface and a retained draft. */
final class NoteEditor {
    private static final String EDITOR_ORIGIN = "https://notes.stride.invalid/editor/";
    private final ComponentActivity activity;
    private final NotesViewModel viewModel;
    private final DialogNoteEditorBinding binding;
    private final Dialog dialog;
    private final Observer<NotesState> observer = state -> syncSavingState();
    private final TextWatcher titleWatcher = new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence text, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence text, int start, int before, int count) {
            viewModel.updateTitle(text.toString());
            renderControls();
        }

        @Override
        public void afterTextChanged(Editable text) {
        }
    };
    private AlertDialog discardConfirmation;
    private boolean ready;
    private boolean reading;
    private boolean wasSaving;
    private boolean closed;

    NoteEditor(ComponentActivity activity, NotesViewModel viewModel, Runnable onDismiss) {
        this.activity = activity;
        this.viewModel = viewModel;
        binding = DialogNoteEditorBinding.inflate(activity.getLayoutInflater());
        binding.titleInput.setText(viewModel.draftTitle());
        binding.titleInput.addTextChangedListener(titleWatcher);
        binding.closeButton.setOnClickListener(v -> requestClose());
        binding.saveButton.setOnClickListener(v -> save());
        dialog = new Dialog(activity, android.R.style.Theme_Material_Light_NoActionBar) {
            @Override
            public void cancel() {
                requestClose();
            }
        };
        dialog.setContentView(binding.getRoot());
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnDismissListener(d -> {
            closed = true;
            viewModel.state().removeObserver(observer);
            binding.titleInput.removeTextChangedListener(titleWatcher);
            if (discardConfirmation != null) {
                discardConfirmation.dismiss();
            }
            binding.contentEditor.stopLoading();
            binding.contentEditor.removeJavascriptInterface("NotesBridge");
            ((ViewGroup) binding.contentEditor.getParent()).removeView(binding.contentEditor);
            binding.contentEditor.destroy();
            onDismiss.run();
        });
        configureInsets();
        configureWebEditor();
        viewModel.state().observe(activity, observer);
        renderControls();
    }

    void show() {
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

    void close() {
        // Screen teardown preserves the draft; only explicit discard clears it.
        if (!closed) {
            closed = true;
            dialog.dismiss();
        }
    }

    private void configureInsets() {
        Window window = dialog.getWindow();
        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, false);
            window.setStatusBarColor(Color.WHITE);
            window.setNavigationBarColor(Color.WHITE);
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            Insets keyboard = insets.getInsets(WindowInsetsCompat.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, keyboard.bottom));
            return insets;
        });
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebEditor() {
        WebView web = binding.contentEditor;
        WebSettings settings = web.getSettings();
        // Only the packaged editor runs JavaScript. Note HTML is sanitized before insertion.
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setDomStorageEnabled(false);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setGeolocationEnabled(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
        web.addJavascriptInterface(new ContentBridge(), "NotesBridge");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return true;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return true;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                // CSP permits remote images, while local files and all navigation stay blocked.
                if (!request.isForMainFrame() && "https".equalsIgnoreCase(request.getUrl().getScheme())) {
                    return null;
                }
                return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (closed || ready || !EDITOR_ORIGIN.equals(url)) {
                    return;
                }
                String script = "(function(){if(!window.notesEditor)return false;notesEditor.load("
                        + quote(viewModel.draftContent()) + "," + quote(activity.getString(R.string.notes_body_hint))
                        + ");return true;})()";
                web.evaluateJavascript(script, value -> {
                    if (closed) {
                        return;
                    }
                    ready = "true".equals(value);
                    binding.editorLoading.setVisibility(View.GONE);
                    if (!ready) {
                        showError(R.string.notes_editor_failed);
                    }
                    renderControls();
                });
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (!closed && request.isForMainFrame()) {
                    ready = false;
                    binding.editorLoading.setVisibility(View.GONE);
                    showError(R.string.notes_editor_failed);
                    renderControls();
                }
            }
        });
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                activity.getAssets().open("note_editor.html"), StandardCharsets.UTF_8))) {
            StringBuilder html = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                html.append(line).append('\n');
            }
            web.loadDataWithBaseURL(EDITOR_ORIGIN, html.toString(), "text/html", "UTF-8", null);
        } catch (IOException exception) {
            binding.editorLoading.setVisibility(View.GONE);
            showError(R.string.notes_editor_failed);
        }
    }

    private void save() {
        if (!ready || reading || viewModel.isSaving()) {
            return;
        }
        binding.formError.setVisibility(View.GONE);
        readContent(() -> viewModel.save(activity.getString(R.string.notes_untitled), success -> {
            if (closed) {
                return;
            }
            if (!success) {
                showError(R.string.notes_save_failed);
            }
            renderControls();
        }));
    }

    private void readContent(Runnable action) {
        reading = true;
        renderControls();
        binding.contentEditor.evaluateJavascript(
                "(function(){notesEditor.setEnabled(false);return notesEditor.read();})()", value -> {
                    if (closed) {
                        return;
                    }
                    reading = false;
                    try {
                        Object decoded = new JSONTokener(value == null ? "null" : value).nextValue();
                        if (!(decoded instanceof String)) {
                            throw new JSONException("Editor content unavailable");
                        }
                        viewModel.updateContent((String) decoded);
                        action.run();
                    } catch (JSONException exception) {
                        showError(R.string.notes_editor_failed);
                    }
                    if (!closed) {
                        renderControls();
                    }
                });
    }

    private void requestClose() {
        if (closed || reading || viewModel.isSaving() || discardConfirmation != null) {
            return;
        }
        if (ready) {
            readContent(this::confirmClose);
        } else {
            confirmClose();
        }
    }

    private void confirmClose() {
        if (!viewModel.hasChanges()) {
            viewModel.finishEditing();
            closed = true;
            dialog.dismiss();
            return;
        }
        discardConfirmation = new AlertDialog.Builder(activity)
                .setTitle(R.string.notes_discard_title)
                .setMessage(R.string.notes_discard_message)
                .setNegativeButton(R.string.notes_keep_editing, null)
                .setPositiveButton(R.string.notes_discard, (d, which) -> {
                    viewModel.finishEditing();
                    closed = true;
                    dialog.dismiss();
                })
                .create();
        discardConfirmation.setOnDismissListener(d -> discardConfirmation = null);
        discardConfirmation.show();
    }

    private void syncSavingState() {
        if (closed) {
            return;
        }
        boolean saving = viewModel.isSaving();
        if (wasSaving && !saving && !viewModel.hasChanges() && !viewModel.isNewNote()) {
            binding.titleInput.setText(viewModel.draftTitle());
            if (ready) {
                binding.contentEditor.evaluateJavascript(
                        "notesEditor.markSaved(" + quote(viewModel.draftContent()) + ")", null);
            }
        }
        wasSaving = saving;
        renderControls();
    }

    private void renderControls() {
        if (closed) {
            return;
        }
        boolean busy = reading || viewModel.isSaving();
        binding.titleInput.setEnabled(!busy);
        binding.closeButton.setEnabled(!busy);
        binding.saveButton.setEnabled(ready && !busy && (viewModel.hasChanges() || viewModel.isNewNote()));
        binding.saveButton.setText(viewModel.isSaving() ? R.string.notes_saving : R.string.notes_save);
        binding.editorStatus.setText(viewModel.isSaving() ? R.string.notes_saving
                : !ready ? R.string.notes_editor_loading
                : viewModel.hasChanges() ? R.string.notes_unsaved
                : viewModel.isNewNote() ? R.string.notes_draft : R.string.notes_saved);
        int statusColor = viewModel.hasChanges() ? R.color.notes_accent
                : ready && !busy && !viewModel.isNewNote() ? R.color.notes_success : R.color.notes_muted;
        binding.editorStatus.setTextColor(ContextCompat.getColor(activity, statusColor));
        if (ready) {
            binding.contentEditor.evaluateJavascript("notesEditor.setEnabled(" + !busy + ")", null);
        }
    }

    private void showError(int message) {
        binding.formError.setText(message);
        binding.formError.setVisibility(View.VISIBLE);
    }

    private static String quote(String value) {
        return JSONObject.quote(value).replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
    }

    private final class ContentBridge {
        @JavascriptInterface
        public void changed(String html) {
            activity.runOnUiThread(() -> {
                if (!closed && ready && !reading && !viewModel.isSaving()) {
                    viewModel.updateContent(html);
                    renderControls();
                }
            });
        }
    }
}
