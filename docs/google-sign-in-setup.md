# Optional Google sign-in setup

Google sign-in is optional. AI Notes also works with verified email/password accounts. Complete [AI Notes and email setup](notes-ai-setup.md) first.

The Android Credential Manager obtains a Google ID token and exchanges it for a Supabase Auth session. Both login methods call `public.resolve_profile()` and use the private `stride_auth.account_profiles` mapping. Existing Google mappings are preserved by [email_auth.sql](../supabase/email_auth.sql).

## Google Cloud configuration

In one Google Cloud project:

1. Configure Google Auth Platform branding, audience, and OpenID/email/profile consent. Add test accounts if the project is in testing.
2. Create a **Web application** OAuth client. Record its client ID for Android and Supabase; put its client SECRET only in Supabase provider settings.
3. Add the callback URL shown in Supabase's Google provider settings to that Web client's authorized redirect URIs, normally `https://YOUR_PROJECT_REF.supabase.co/auth/v1/callback`.
4. Create an **Android** OAuth client for `com.stride.android` and the installed app's SHA-1 signing certificate. Register appropriate debug, release, and Google Play App Signing certificates.

This implementation needs the Web client ID as its server client ID. It does not require `google-services.json` or an Android browser deep link.

## Supabase and Android configuration

Enable Google in the same DEVELOPMENT Supabase project's Authentication settings. Configure the Web client ID/secret and keep nonce verification enabled. The app sends a nonce hash to Google and the original nonce to Supabase.

Add the public client ID to the ignored `local.properties`, preserving the SDK path:

```properties
stride.googleWebClientId=YOUR_WEB_CLIENT_ID.apps.googleusercontent.com
```

Alternatively supply `-Pstride.googleWebClientId=YOUR_WEB_CLIENT_ID.apps.googleusercontent.com` to Gradle. Sync and rebuild after changing it. An empty value leaves regular email/password sign-in usable.

Apply [email_auth.sql](../supabase/email_auth.sql) first, then optional [google_sign_in.sql](../supabase/google_sign_in.sql). The companion uses the shared account resolver; it does not install a separate anonymous login policy or a second account mapping.

## Existing profiles

A confirmed email alone never grants access to a legacy Stride profile. Follow administrator verification and `stride_auth.link_verified_legacy_profile` in [the migration instructions](notes-ai-setup.md#4-preserve-existing-accounts). Existing established `google_profiles` mappings are copied by the email migration; do not insert new rows into that old mapping.

Supporting both email and Google methods for one person requires them to belong to the same Supabase Auth user ID. Use Supabase's supported identity linking/account-recovery process; do not merge Stride rows or mappings by matching email addresses. Email/password recovery can establish a password for an eligible verified Auth account.

## Verification

On a device with Google Play services, check a new Google login, cancellation, session restore, sign-out, and switching accounts. Verify the resolved profile ID and its data remain correct. Repeat ownership checks with a second account and anonymous requests. Separately verify email/password works with the Google provider disabled.

No provider configuration, live migration, deployment, build, or device test has been performed here.

References: [Google Credential Manager](https://developer.android.com/identity/sign-in/credential-manager-siwg-implementation), [Supabase Google provider](https://supabase.com/docs/guides/auth/social-sign-in/auth-google), [Supabase identity linking](https://supabase.com/docs/guides/auth/auth-identity-linking).
