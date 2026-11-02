# AI Notes setup

This implementation adds a Stride study workspace inspired by NotebookLM: private PDF sources, source selection, Gemini chat with available PDF citations, summaries, study guides, flashcards, quizzes, and saving generated material as a new note. It does not connect to NotebookLM or its notebooks. Google sign-in is optional; verified Supabase email/password accounts work with every Notes feature.

The code and SQL are prepared for manual setup. No live migrations, deployment, build, or device test was performed.

## 1. Select the development project

Before running SQL, verify the URL and publishable key in `app/src/main/java/com/stride/android/utils/SupabaseApi.java` point to your DEVELOPMENT Supabase project. Use that same project for SQL, Authentication, Storage, and the Edge Function. Keep privileged keys out of the Android application.

Review existing tables, policies, triggers, and backups first. Required existing tables are `public.users`, `notes`, `tasks`, `goals`, and `calendar_events`, with UUID profile ownership (`users.id`, other tables' `user_id`). The profile requires `full_name`, `email`, `password`, and `features`; existing defaults must supply any other mandatory columns. These scripts do not create the original application schema.

## 2. Configure email authentication

In Supabase Authentication settings:

1. Enable Email/password and email confirmation. Set a minimum password length of at least 8 characters. Set up an SMTP provider appropriate for your users and sending volume.
2. In the **Confirm signup** and **Reset password** email templates, include the literal template expression `{{ .Token }}` as a code. The Android verification dialog accepts 6–10 digits.
3. Keep secure email changes enabled. The **Change email address** template must include both `{{ .ConfirmationURL }}` and `{{ .Token }}`: users confirm the CURRENT address through its link first, then enter the NEW address's code in Stride.
4. Configure a reachable HTTPS Site URL for browser email-change confirmations. Signup and password recovery use in-app codes, so they do not need an Android callback/deep link.
5. Do not add CAPTCHA enforcement without implementing the corresponding native challenge flow. Provider rate limits and email delivery settings still apply.

Suggested code template content (keep your branding):

```html
<h2>Your Stride verification code</h2>
<p>{{ .Token }}</p>
<p>If you did not request this, ignore this email.</p>
```

For the email-change template, also include:

```html
<p><a href="{{ .ConfirmationURL }}">Confirm this address</a></p>
```

Supabase references: [password authentication](https://supabase.com/docs/guides/auth/passwords), [email templates](https://supabase.com/docs/guides/auth/auth-email-templates), [OTP verification](https://supabase.com/docs/reference/javascript/auth-verifyotp).

## 3. Apply the migrations in order

Run each ENTIRE file in the development project's SQL Editor as the database owner:

1. [email_auth.sql](../supabase/email_auth.sql)
2. [notes_ai.sql](../supabase/notes_ai.sql)
3. Optional: [google_sign_in.sql](../supabase/google_sign_in.sql), if you retain the Google provider. Follow [the Google guide](google-sign-in-setup.md) for its separate OAuth configuration.

The existing deadline migration is independent; retain it if you use deadline timing.

`email_auth.sql` deliberately disables the former anonymous users-table login and email-only restore. Old application versions using that path will stop working. Update every client relying on it. Profiles, profile IDs, notes, tasks, goals, calendar events, and established Google mappings are retained. No account is linked by guessing its email.

The private `stride_auth.account_profiles` mapping is used for both email and Google sessions. Keep `stride_auth` OUT of the Data API's exposed schemas. Schema USAGE granted for policy evaluation does not grant access to its tables. The resolver returns only profile fields, not legacy passwords.

Ownership policies are restrictive, so an old permissive policy cannot override them. Existing restrictive policies can still block legitimate requests: inspect and adapt such application-specific policies rather than disabling RLS. Review any other views, functions, tables, or services that independently expose personal records.

`notes_ai.sql` creates source metadata, chat, study outputs, daily usage and request ledgers, mutation leases, and the private `stride-note-pdfs` bucket. Client roles can read their own AI rows; only the Edge Function can write them. Storage guards protect this bucket even if unrelated broad Storage policies exist. Do not make the bucket public. Its originals and search indexes are removed through the function, not through direct REST deletion.

## 4. Preserve existing accounts

New accounts with no previous Stride profile can sign up, verify their code, and sign in normally.

Existing users must create/verify a Supabase Auth identity, then have an administrator independently verify ownership of BOTH that identity and their old Stride account. The resolver reports that linking is required and does not open the old data. Matching an email, showing a screenshot, or relying on the former email-only session is insufficient verification.

After independent verification, inspect the development records without selecting legacy passwords:

```sql
select id, email, email_confirmed_at
from auth.users
where lower(email) = lower('verified-user@example.com');

select id, full_name, email
from public.users
where lower(email) = lower('verified-user@example.com');
```

As database owner, replace the following placeholders with the VERIFIED UUIDs:

```sql
begin;
select stride_auth.link_verified_legacy_profile(
    'VERIFIED_AUTH_USER_UUID'::uuid,
    'VERIFIED_EXISTING_PROFILE_UUID'::uuid
);
commit;
```

This administrator-only helper checks the confirmed matching email, refuses an existing conflicting mapping, preserves the old profile ID and related records, and replaces its legacy password with an unusable random compatibility value. The helper's checks supplement the administrator's independent verification; they do not replace it. The user signs in again after linking. Existing established Google mappings are copied automatically; do not relink them.

Do not import plaintext legacy passwords into Auth or automatically merge profiles by email. Foreign keys prevent deleting a linked Auth identity/profile before an administrator handles its mapping and related data. Account-wide deletion must also remove Notes PDFs and Gemini indexes.

## 5. Create the Gemini server secret

Create a Gemini API key in [Google AI Studio](https://aistudio.google.com/apikey) for a project with the required model and File Search access. A developer/administrator needs this key; each Stride user does not need a Google account or their own key.

In Supabase **Edge Functions → Secrets**, add:

| Secret | Value |
| --- | --- |
| `GEMINI_API_KEY` | Your Gemini API key |
| `GEMINI_MODEL` | Optional; defaults to `gemini-3.8-flash` |
| `GEMINI_EMBEDDING_MODEL` | Optional; defaults to `models/gemini-embedding-2` |
| `NOTE_AI_ALLOWED_ORIGINS` | Optional comma-separated exact web origins; leave empty for the native Android client |

Supabase supplies `SUPABASE_URL`, `SUPABASE_ANON_KEY`, and `SUPABASE_SERVICE_ROLE_KEY` to hosted functions. The service-role key is used only inside the function.

The implementation uses Gemini's Interactions REST API with File Search. If changing models, choose one supporting File Search and structured JSON output together. Existing indexes keep their embedding model; changing the embedding setting only affects new indexes.

File Search index creation and Gemini input/output can be billable. Set billing budgets and monitor provider/Supabase usage. This feature is not a promise of free NotebookLM-equivalent service. References: [Gemini File Search](https://ai.google.dev/gemini-api/docs/file-search), [Interactions API](https://ai.google.dev/api/interactions-api), [pricing](https://ai.google.dev/gemini-api/docs/pricing).

## 6. Deploy the function

Deploy [functions/notes-ai/index.ts](../supabase/functions/notes-ai/index.ts) as `notes-ai` to the SAME development project. With the Supabase CLI, from the repository root:

```powershell
supabase login
supabase functions deploy notes-ai --project-ref YOUR_DEVELOPMENT_PROJECT_REF --no-verify-jwt
```

Initialize the Supabase CLI project first if your local CLI requires a config file. No external Deno dependencies are required.

`--no-verify-jwt` disables only the gateway's legacy JWT check. The handler still requires a bearer token, verifies it through Supabase Auth's `/user` endpoint, resolves a confirmed identity's profile using that user's JWT, and verifies notebook ownership BEFORE using service-role access. Do not remove those checks. This supports current Supabase signing keys and the app's publishable key.

Normal note editing does not call Gemini. Notebook deletion does call this function to remove attachments/indexes safely, so deploy it even when testing a notebook with no PDFs.

## 7. Limits, retries, and retention

| Limit | Enforced value |
| --- | --- |
| PDF size | 10 MB |
| Pages | 1–1,000; readable, unencrypted PDF |
| Sources | 10 per notebook; 20 per account |
| Selected PDFs per AI request | 3 |
| Upload reservations per account per UTC day | 10 |
| Index preparation attempts per account per UTC day | 20 |
| Chat/study generation requests per account per UTC day | 60 |
| Current note context | First 24,000 characters after HTML stripping |
| Visible conversation / study history | Latest 50 entries each |
| Conversation context | Last 6 exchanges using exactly the same selected source set |

Quotas are server-side and do not reset when a notebook or source is deleted. Failed requests can consume a quota reservation and provider resources. These are application limits in addition to provider limits.

Uploads use signed URLs and bounded private cache files. A failed upload leaves a pending source visible after refresh: remove it and attach the PDF again. Preparation failures can be retried. An interrupted function lease expires after three minutes. Chat/generation retries reuse a request ID to recover a saved result; abandoned requests become failed after their lease window. The app does not silently replace failed AI output with fabricated text.

Each PDF has its own search store; only selected ready stores are supplied to Gemini. Previous chat from a different source selection is excluded from the prompt. The current saved note is always included. Citations are shown only when Gemini returns a matching attached source; page links appear only for valid returned page numbers. A response can lack citations. Generation remains probabilistic: source references do not guarantee an answer is correct.

Chat and study material persist until notebook deletion. Removing one PDF leaves historical answers and study items in place, with source links that no longer open. Signed download links last 60 seconds; outstanding upload capabilities can outlive source removal until their Storage expiry. Provider indexes are deleted through the function, and temporary orphan files/indexes are cleaned below. `store:false` disables stored Gemini interactions; provider service policies still govern processing.

## 8. Schedule orphan cleanup

Use a trusted SERVER scheduler to POST to `/functions/v1/notes-ai` with a service-role bearer and:

```json
{"action":"maintenance"}
```

Never ship this bearer in the APK, a public URL, a client-side scheduler, or source control. Maintenance is rejected for ordinary users.

The endpoint removes up to 100 bucket objects older than 24 hours with no source metadata, and scans up to 100 Gemini stores per call. It deletes only unreferenced stores older than 24 hours whose display name matches Stride's source-UUID convention. Repeat calls until `removed_files` is below 100. If `next_page_token` is nonempty, send it as `page_token` in the next call; continue until empty. Schedule a complete traversal daily, retaining the cursor in the scheduler when necessary. Retry failed cleanup calls. This is required for interrupted upload/index transactions; deploying the function does not install a scheduler.

Do not share this Gemini project with another Stride environment using the same store naming convention. A dedicated development/production Gemini project prevents one environment's cleanup from treating another environment's stores as orphans.

## 9. Device verification for you to run

- Sign up using email/password with Google disabled, verify the code, and sign in. Check recovery, sign-out, session restore, and email changes.
- Link a verified legacy test account and confirm all old notes/tasks retain their original ownership.
- Create/save a note. Tap its notebook card to open Sources / Chat / Study tools. **Edit note** preserves the existing rich editor.
- Attach a readable PDF, wait for Ready, select it, preview it, and ask a question whose answer is in that file. Check each returned reference/page against the original.
- Deselect a source and confirm subsequent requests use only the selected set plus the saved note. Try an unsupported question and review the uncertainty response.
- Generate each study format. Reveal flashcards, answer quizzes, navigate questions, and save an output as a new editable note.
- Rotate while the Notes workspace is open; verify the draft question, source selections, in-flight operation, and study progress after reopening the generated item. Process death starts a fresh workspace; backend history remains.
- Try a large, corrupt, encrypted, and cancelled PDF, an interrupted upload/index, missing Gemini secret, and exhausted quota. Refresh/retry and check the error state.
- Remove a source and delete a notebook. Verify private Storage originals and Gemini indexes are removed; then exercise scheduled orphan cleanup.
- With two accounts, tamper with notebook/source/artifact IDs. Verify cross-account actions fail, ordinary JWTs cannot call maintenance/admin RPCs, anonymous reads fail, and direct AI-table/Storage writes fail.

If the UI reports incomplete setup, confirm BOTH SQL files were applied to the app's configured project, schema reload completed, the function is deployed there, and the Gemini secret/model is available. SQL is prepared against the repository's expected schema; custom production constraints require review before rollout.
