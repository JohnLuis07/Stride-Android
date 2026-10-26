# Stride Android

A separate native Android application written in Java. It does not share or modify the React/Vite web application.

## Included

- Native Java activity and views for dashboard, tasks, focus timer, notes, goals, calendar, and profile
- On-device persistence via `SharedPreferences`
- Android API 24+ support; targets API 35

## Open in Android Studio

1. Open this repository as a project.
2. Let Android Studio install/choose Android SDK Platform 35.
3. Run the `app` configuration on an emulator or Android device.

## Code organization

Java sources live under `app/src/main/java/com/stride/android/`:

- `MainActivity.java`: screen hosting, navigation, and activity lifecycle.
- `ui/`: View Binding owners, form validation, and screen event handlers.
- `viewmodel/`: session/profile operations, observable list results, and retained focus timer state.
- `model/`: immutable list and timer display state.
- `adapter/`: the shared binding-backed RecyclerView `ListAdapter`.
- `utils/`: the Supabase REST client and common form/formatting helpers.

Tasks, notes, goals, and calendar events share list loading and row rendering. Screen
observers are detached when navigation replaces a screen. Timer state survives
navigation and activity recreation within the same process.

## Backend

`utils/SupabaseApi.java` connects to Supabase REST tables. `SessionViewModel`
encapsulates the existing users-table sign-in flow, saved session email, and profile
updates. This cleanup preserves the current backend contract.

## Manual verification

After syncing Gradle in Android Studio, verify sign-in/sign-up, drawer and back
navigation, list loading and error states, creating each record type, calendar date
filtering, profile and feature updates, and timer pause/reset/technique selection.
Also check a long list, a small screen with the keyboard open, and activity rotation
while the focus timer is running. Build, lint, and device execution were not completed
as part of this source cleanup.
