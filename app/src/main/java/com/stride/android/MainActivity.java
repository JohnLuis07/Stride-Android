package com.stride.android;

import android.Manifest;
import android.app.AlertDialog;
import android.app.AlarmManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.lifecycle.ViewModelProvider;

import com.stride.android.databinding.ActivityMainBinding;
import com.stride.android.ui.CalendarScreen;
import com.stride.android.ui.AllowanceScreen;
import com.stride.android.ui.DashboardScreen;
import com.stride.android.ui.FeatureScreen;
import com.stride.android.ui.FocusScreen;
import com.stride.android.ui.GoalsScreen;
import com.stride.android.ui.LoginScreen;
import com.stride.android.ui.LoadingView;
import com.stride.android.ui.NotesScreen;
import com.stride.android.ui.SettingsScreen;
import com.stride.android.ui.SignupScreen;
import com.stride.android.ui.TasksScreen;
import com.stride.android.ui.VideoGenerationScreen;
import com.stride.android.viewmodel.FocusViewModel;
import com.stride.android.viewmodel.SessionViewModel;
import com.stride.android.utils.DeadlineReminders;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.Locale;

/** Hosts binding-backed screens and coordinates navigation and session state. */
public class MainActivity extends ComponentActivity {
    private ActivityMainBinding mainBinding;
    private FrameLayout drawer;
    private FeatureScreen activeScreen;
    private SessionViewModel session;
    private FocusViewModel focus;
    private String activePage = "Overview";
    private AlertDialog signOutDialog;
    private AlertDialog reminderPermissionDialog;
    private final ActivityResultLauncher<String> notificationPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted && session != null && session.user() != null) {
                    DeadlineReminders.get(this).reconcile();
                    requestExactReminderAccess();
                }
            });

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        session = new ViewModelProvider(this).get(SessionViewModel.class);
        focus = new ViewModelProvider(this).get(FocusViewModel.class);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (drawer != null && drawer.getVisibility() == View.VISIBLE) {
                    closeDrawer();
                } else if (session.user() != null && !"Overview".equals(activePage)) {
                    showDashboard();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                    setEnabled(true);
                }
            }
        });
        LoadingView startup = new LoadingView(this, null, null);
        startup.showLoading(R.string.workspace_loading_session, R.string.workspace_loading_session_message);
        setContentView(startup.root());
        applySystemBarInsets(startup.root());
        session.restore(error -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            if (error == null) {
                requestReminderPermissions();
                if (openReminderPage()) return;
                if (state != null && "Notes".equals(state.getString("active_page"))) showNotes();
                else if (state != null && "Tasks".equals(state.getString("active_page"))) showTasks();
                else if (state != null && "Goals".equals(state.getString("active_page"))) showGoals();
                else if (state != null && "GenerateVideos".equals(state.getString("active_page"))) showGenerateVideos();
                else if (state != null && "Allowance".equals(state.getString("active_page"))) showAllowance();
                else showDashboard();
            } else {
                signOut();
            }
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        openReminderPage();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (session != null && session.user() != null) {
            DeadlineReminders reminders = DeadlineReminders.get(this);
            reminders.reconcile();
            reminders.refresh(session.api(), session.user().optString("id"));
        }
    }

    private boolean openReminderPage() {
        if (session == null || session.user() == null || getIntent() == null) return false;
        String page = getIntent().getStringExtra(DeadlineReminders.EXTRA_PAGE);
        String owner = getIntent().getStringExtra(DeadlineReminders.EXTRA_OWNER);
        getIntent().removeExtra(DeadlineReminders.EXTRA_PAGE);
        getIntent().removeExtra(DeadlineReminders.EXTRA_OWNER);
        if (!session.user().optString("id").equals(owner)) return false;
        if ("Tasks".equals(page)) {
            showTasks();
            return true;
        }
        if ("Goals".equals(page)) {
            showGoals();
            return true;
        }
        return false;
    }

    private void requestReminderPermissions() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            if (!getPreferences(MODE_PRIVATE).getBoolean("reminder_permission_asked", false)) {
                getPreferences(MODE_PRIVATE).edit().putBoolean("reminder_permission_asked", true).apply();
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
            return;
        }
        DeadlineReminders.get(this).reconcile();
        requestExactReminderAccess();
    }

    private void requestExactReminderAccess() {
        if (Build.VERSION.SDK_INT < 31 || isFinishing() || isDestroyed()
                || getSystemService(AlarmManager.class).canScheduleExactAlarms()
                || getPreferences(MODE_PRIVATE).getBoolean("reminder_exact_asked", false)
                || reminderPermissionDialog != null) return;
        getPreferences(MODE_PRIVATE).edit().putBoolean("reminder_exact_asked", true).apply();
        reminderPermissionDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.reminder_timing_title)
                .setMessage(R.string.reminder_timing_message)
                .setNegativeButton(R.string.reminder_later, null)
                .setPositiveButton(R.string.reminder_allow, (dialog, which) -> {
                    Intent settings = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse("package:" + getPackageName()));
                    try { startActivity(settings); }
                    catch (android.content.ActivityNotFoundException ignored) {
                        // Inexact alarms remain available on devices without this settings screen.
                    }
                }).create();
        reminderPermissionDialog.setOnDismissListener(dialog -> reminderPermissionDialog = null);
        reminderPermissionDialog.show();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putString("active_page", activePage);
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onPause() {
        super.onPause();
        focus.save();
    }

    @Override
    protected void onDestroy() {
        if (reminderPermissionDialog != null) reminderPermissionDialog.dismiss();
        if (signOutDialog != null) {
            signOutDialog.dismiss();
        }
        closeScreen();
        super.onDestroy();
    }

    private void closeScreen() {
        if (activeScreen != null) {
            activeScreen.close();
            activeScreen = null;
        }
    }

    private void shell(
            String title,
            FeatureScreen screen
    ) {
        closeScreen();
        activeScreen = screen;
        mainBinding = ActivityMainBinding.inflate(
                getLayoutInflater()
        );

        setContentView(mainBinding.getRoot());
        applySystemBarInsets(mainBinding.getRoot());

        drawer = mainBinding.drawerOverlay;

        boolean canGoBack =
                !activePage.equals("Overview");

        if (canGoBack) {
            mainBinding.toolbarLogoContainer.setVisibility(
                    View.GONE
            );
            mainBinding.toolbarTitle.setVisibility(
                    View.VISIBLE
            );
            mainBinding.toolbarTitle.setText(title);
        } else {
            mainBinding.toolbarTitle.setVisibility(
                    View.GONE
            );
            mainBinding.toolbarLogoContainer.setVisibility(
                    View.VISIBLE
            );
        }

        mainBinding.backButton.setVisibility(
                canGoBack
                        ? View.VISIBLE
                        : View.GONE
        );

        mainBinding.backButton.setOnClickListener(
                v -> showDashboard()
        );

        mainBinding.menuButton.setOnClickListener(
                v -> openDrawer()
        );

        mainBinding.contentHost.addView(
                screen.root()
        );

        buildDrawer();
    }

    private void buildDrawer() {
        updateDrawerProfile();
        drawer.setOnClickListener(v -> closeDrawer());
        mainBinding.drawerPanel.setOnClickListener(v -> {
            // Keep taps inside the panel from dismissing the overlay.
        });
        mainBinding.drawerClose.setOnClickListener(v -> closeDrawer());
        ViewCompat.setAccessibilityPaneTitle(mainBinding.drawerPanel,
                getString(R.string.workspace_navigation));

        bindNavigation(mainBinding.navOverview, "Overview", this::showDashboard);
        bindNavigation(mainBinding.navTasks, "Tasks", this::showTasks);
        bindNavigation(mainBinding.navCalendar, "Calendar", this::showCalendar);
        bindNavigation(mainBinding.navNotes, "Notes", this::showNotes);
        bindNavigation(mainBinding.navGoals, "Goals", this::showGoals);
        bindNavigation(mainBinding.navFocus, "Focus", this::showFocus);
        bindNavigation(mainBinding.navAllowance, "Allowance", this::showAllowance);
        bindNavigation(mainBinding.navSettings, "Settings", this::showProfile);
        bindNavigation(mainBinding.navLogout, "", this::confirmSignOut);
        updateDrawerFeatures();

        View panel = mainBinding.drawerPanel;
        mainBinding.getRoot().addOnLayoutChangeListener(
                (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                    int availableWidth = right - left;
                    if (availableWidth <= 0) {
                        return;
                    }
                    float density = getResources().getDisplayMetrics().density;
                    int panelWidth = Math.min(Math.round(320 * density),
                            Math.max(0, availableWidth - Math.round(48 * density)));
                    ViewGroup.LayoutParams params = panel.getLayoutParams();
                    if (params.width != panelWidth) {
                        params.width = panelWidth;
                        panel.setLayoutParams(params);
                    }
                });
    }

    private void bindNavigation(View item, String page, Runnable navigate) {
        item.setSelected(page.equals(activePage));
        item.setOnClickListener(v -> {
            closeDrawer();
            navigate.run();
        });
    }

    private void updateDrawerProfile() {
        if (session.user() == null) {
            return;
        }
        String name = session.user().optString("full_name").trim();
        mainBinding.drawerName.setText(name.isEmpty()
                ? getString(R.string.workspace_account_default) : name);
        mainBinding.drawerIdentity.setText(session.user().optString("email"));
        mainBinding.drawerAvatar.setText(initials(name));
    }

    private void updateDrawerFeatures() {
        if (mainBinding == null || session.user() == null) {
            return;
        }
        JSONArray features = session.user().optJSONArray("features");
        if (features == null) {
            try {
                features = new JSONArray(session.user().optString("features", ""));
            } catch (JSONException ignored) {
                // Match Settings: absent or invalid preferences use the default selection.
            }
        }
        setDrawerFeatureVisibility(mainBinding.navTasks, features, "Todo");
        setDrawerFeatureVisibility(mainBinding.navCalendar, features, "Calendar");
        setDrawerFeatureVisibility(mainBinding.navNotes, features, "Notes");
        setDrawerFeatureVisibility(mainBinding.navGoals, features, "Goals");
        setDrawerFeatureVisibility(mainBinding.navFocus, features, "Focus");
        setDrawerFeatureVisibility(mainBinding.navAllowance, features, "Allowance");
    }

    private void setDrawerFeatureVisibility(View item, JSONArray features, String key) {
        boolean selected = features == null;
        if (features != null) {
            for (int i = 0; i < features.length(); i++) {
                if (key.equals(features.optString(i))) {
                    selected = true;
                    break;
                }
            }
        }
        item.setVisibility(selected ? View.VISIBLE : View.GONE);
    }

    private String initials(String name) {
        if (name.isEmpty()) {
            return "S";
        }
        String[] parts = name.split("\\s+");
        String first = new String(Character.toChars(parts[0].codePointAt(0)));
        if (parts.length > 1) {
            first += new String(Character.toChars(parts[parts.length - 1].codePointAt(0)));
        }
        return first.toUpperCase(Locale.getDefault());
    }

    private void openDrawer() {
        if (drawer != null) {
            updateDrawerProfile();
            updateDrawerFeatures();
            mainBinding.workspaceContent.setImportantForAccessibility(
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            drawer.setVisibility(View.VISIBLE);
            mainBinding.drawerClose.requestFocus();
        }
    }

    private void closeDrawer() {
        if (drawer != null) {
            drawer.setVisibility(View.GONE);
            mainBinding.workspaceContent.setImportantForAccessibility(
                    View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
            mainBinding.menuButton.requestFocus();
        }
    }

    private boolean loggedIn() {
        if (session.user() == null) {
            showLogin();
            return false;
        }
        return true;
    }

    private void showAuthScreen(FeatureScreen screen) {
        closeScreen();
        activeScreen = screen;
        mainBinding = null;
        drawer = null;
        setContentView(screen.root());
        applySystemBarInsets(screen.root());
    }

    private void showLogin() {
        LoginScreen screen = new LoginScreen(this, session);
        showAuthScreen(screen);
        screen.bind(this::openWorkspace, this::showSignup);
    }

    private void showSignup() {
        SignupScreen screen = new SignupScreen(this, session);
        showAuthScreen(screen);
        screen.bind(() -> {
            if (session.user() != null) openWorkspace();
            else showLogin();
        }, this::showLogin);
    }

    private void openWorkspace() {
        requestReminderPermissions();
        closeScreen();
        mainBinding = null;
        drawer = null;
        LoadingView welcome = new LoadingView(this, null, null);
        welcome.showLoading(R.string.workspace_loading_welcome, R.string.workspace_loading_welcome_message);
        setContentView(welcome.root());
        applySystemBarInsets(welcome.root());
        // Allow the loading view to draw before binding the workspace, without a timed delay.
        welcome.root().postOnAnimation(() -> welcome.root().post(() -> {
            if (!isFinishing() && !isDestroyed() && welcome.root().isAttachedToWindow()) {
                if (!openReminderPage()) showDashboard();
            }
        }));
    }

    private void confirmSignOut() {
        if (isFinishing() || isDestroyed() || session.user() == null || signOutDialog != null) {
            return;
        }
        signOutDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.settings_sign_out_title)
                .setMessage(R.string.settings_sign_out_message)
                .setNegativeButton(R.string.settings_cancel, null)
                .setPositiveButton(R.string.settings_sign_out, (dialog, which) -> signOut())
                .create();
        signOutDialog.setOnDismissListener(dialog -> signOutDialog = null);
        signOutDialog.show();
    }

    private void signOut() {
        focus.stop();
        session.signOut();
        showLogin();
    }

    private void showDashboard() {
        if (!loggedIn()) {
            return;
        }
        activePage = "Overview";
        DashboardScreen screen = new DashboardScreen(this, session.user());
        shell("Stride.", screen);
        screen.bind(this::showNotes, this::showCalendar, this::showTasks,
                this::showGoals, this::showFocus, this::showGenerateVideos, this::showAllowance);
    }

    private void showAllowance() {
        if (!loggedIn()) return;
        activePage = "Allowance";
        AllowanceScreen screen = new AllowanceScreen(this, session.api(), session.user());
        shell(getString(R.string.allowance_title), screen);
        screen.bind();
    }

    private void showGenerateVideos() {
        if (!loggedIn()) return;
        if (!SessionViewModel.hasVideoFeature(session.user())) {
            showDashboard();
            return;
        }
        activePage = "GenerateVideos";
        VideoGenerationScreen screen = new VideoGenerationScreen(this, session.api(), session.user().optString("id"));
        shell(getString(R.string.video_title), screen);
        screen.bind();
    }

    private void showTasks() {
        if (!loggedIn()) {
            return;
        }
        activePage = "Tasks";
        TasksScreen screen = new TasksScreen(this, session.api(), session.user());
        shell("Tasks", screen);
        screen.bind();
    }

    private void showNotes() {
        if (!loggedIn()) {
            return;
        }
        activePage = "Notes";
        NotesScreen screen = new NotesScreen(this, session.api(), session.user());
        shell("Notes", screen);
        screen.bind();
    }

    private void showGoals() {
        if (!loggedIn()) {
            return;
        }
        activePage = "Goals";
        GoalsScreen screen = new GoalsScreen(this, session.api(), session.user());
        shell("Goals", screen);
        screen.bind();
    }

    private void showCalendar() {
        if (!loggedIn()) {
            return;
        }
        activePage = "Calendar";
        CalendarScreen screen = new CalendarScreen(this, session.api(), session.user());
        shell("Smart Calendar", screen);
        screen.bind();
    }

    private void showFocus() {
        if (!loggedIn()) {
            return;
        }
        activePage = "Focus";
        FocusScreen screen = new FocusScreen(this, focus);
        shell("Focus", screen);
        screen.bind();
    }

    private void showProfile() {
        if (!loggedIn()) {
            return;
        }
        activePage = "Settings";
        SettingsScreen screen = new SettingsScreen(this, session, this::confirmSignOut);
        shell("Profile & settings", screen);
        screen.bind();
    }

    private void applySystemBarInsets(View root) {
        int left = root.getPaddingLeft();
        int top = root.getPaddingTop();
        int right = root.getPaddingRight();
        int bottom = root.getPaddingBottom();

        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(
                    left,
                    top + insets.getSystemWindowInsetTop(),
                    right,
                    bottom + insets.getSystemWindowInsetBottom()
            );
            return insets;
        });
        root.requestApplyInsets();
    }
}
