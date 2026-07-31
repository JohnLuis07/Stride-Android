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

## Backend

This starter deliberately does not copy any backend keys from the web app. A subsequent integration can connect this app to Supabase through a dedicated Android authentication and API layer.
