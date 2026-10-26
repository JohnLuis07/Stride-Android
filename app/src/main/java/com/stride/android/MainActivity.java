package com.stride.android;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.core.view.ViewCompat;
import androidx.lifecycle.ViewModelProvider;

import com.stride.android.databinding.ActivityMainBinding;
import com.stride.android.ui.CalendarScreen;
import com.stride.android.ui.DashboardScreen;
import com.stride.android.ui.FeatureScreen;
import com.stride.android.ui.FocusScreen;
import com.stride.android.ui.GoalsScreen;
import com.stride.android.ui.LoginScreen;
import com.stride.android.ui.NotesScreen;
import com.stride.android.ui.SettingsScreen;
import com.stride.android.ui.SignupScreen;
import com.stride.android.ui.TasksScreen;
import com.stride.android.viewmodel.FocusViewModel;
import com.stride.android.viewmodel.SessionViewModel;

import java.util.Locale;

/** Hosts binding-backed screens and coordinates navigation and session state. */
public class MainActivity extends ComponentActivity {
    private ActivityMainBinding mainBinding;
    private FrameLayout drawer;
    private FeatureScreen activeScreen;
    private SessionViewModel session;
    private FocusViewModel focus;
    private String activePage = "Overview";

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
        session.restore(error -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            if (error == null) {
                if (state != null && "Notes".equals(state.getString("active_page"))) showNotes();
                else showDashboard();
            } else {
                signOut();
            }
        });
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
        bindNavigation(mainBinding.navSettings, "Settings", this::showProfile);
        bindNavigation(mainBinding.navLogout, "", this::signOut);

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
        screen.bind(this::showDashboard, this::showSignup);
    }

    private void showSignup() {
        SignupScreen screen = new SignupScreen(this, session);
        showAuthScreen(screen);
        screen.bind(() -> {
            if (session.user() != null) showDashboard();
            else showLogin();
        }, this::showLogin);
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
                this::showGoals, this::showFocus);
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
        SettingsScreen screen = new SettingsScreen(this, session, this::signOut);
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
