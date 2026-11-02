# Video studio setup

Stride creates a 30-second silent reel from six separately generated five-second
scenes. Android trims and joins the scenes into a portrait 720 × 1280 H.264 MP4.
The model runs remotely; no AI model is installed on the phone.

## Deploy or upgrade

1. Use a development Supabase project with `supabase/email_auth.sql` applied.
2. Run the complete updated `supabase/video_generation.sql` in SQL Editor as the
   database owner. Re-running it upgrades the original short-clip implementation:
   it adds reel records, scene metadata, image-generation status and reservations,
   and a private input-image bucket accepting JPEG, PNG and WebP up to 2 MiB.
   The per-profile allowance remains **12 scene attempts per UTC day**.
   Existing clip records are preserved. Do not add client write grants or public
   Storage policies for these tables and buckets.
3. Set `HF_TOKEN` in Edge Function Secrets using your Hugging Face access token.
   The same token is used for image and video generation; no second key is needed.
   The standard Supabase URL, anon key, and service-role key are supplied by
   Supabase. Never put provider tokens or service-role credentials in Android.
4. Redeploy the updated `supabase/functions/generate-video/index.ts` as the Edge
   Function named exactly `generate-video`. For this project's ES256 sessions,
   disable the gateway's legacy JWT verification; this function validates Auth
   and the verified profile itself on every request. CLI example:

   ```sh
   supabase functions deploy generate-video --no-verify-jwt
   ```

5. As an administrator, add the exact string `generatevids` to the intended row's
   existing `public.users.features` JSON array. For example:

   ```json
   ["Todo", "Calendar", "Notes", "Goals", "Focus", "generatevids"]
   ```

6. Sync Gradle, rebuild, and launch Android yourself. The new dependency is
   `androidx.media3:media3-transformer:1.5.1`, pinned for the current compile SDK.
   Sign out and back in to reload the feature flag. Open **Video studio** on the
   dashboard. Settings does not expose this entitlement and preserves it when
   regular dashboard preferences are saved. Removing it administratively blocks
   further backend actions immediately; sign in again to hide the dashboard card.

## Generate and save a reel

Describe your subject, scene and motion, optionally add an image, and tap
**Generate 30-second reel**. You can remove a selected image with **Remove image
and use my prompt**. Without an upload, the backend first creates a starting image
using the hosted FLUX.1-schnell model. This extra step consumes GPU quota and has
its own status; no video scene is submitted until the image is ready. The studio
shows a preview when the private starting image becomes available.

The app saves the starting image privately and submits scenes sequentially. Each
scene is a fresh generation using the same image and prompt with a randomized
seed. Scenes are distinct takes, not repeated copies of one clip. They may have
visible cuts or inconsistent motion; seamless story continuity is not promised.

The timeline shows actual completed scenes, with no simulated generation
percentage. Each scene has its own playback and download controls. After all six
finish, the app downloads them and automatically exports the reel. **Save MP4**
opens Android's file picker so you choose the destination without broad storage
permissions. The combined MP4 is retained in app-private storage until app data
is cleared or the app is removed; it is not uploaded to Supabase.

Keep the studio open to advance from one scene to the next. If you leave it, the
submitted scene can finish on the server; further scenes wait until you return.
After restarting the app, the studio restores the most recent reel, including an
image-generation request that is active or failed. Tap **Continue generation** to
continue or retry a failed image or scene request. Already
completed scenes are preserved. Export failures can be retried with **Combine**
without spending additional generation quota. Save a finished reel before using
**Start another reel**; this version opens only the most recent reel and does not
provide a reel-history browser.

## Free quotas and runtime limits

The server adapter uses the public
[Wan 2.2 image-to-video Space](https://huggingface.co/spaces/zerogpu-aoti/wan2-2-fp8da-aoti-faster)
with four inference steps and five-second clips. Images are downsized to at most
1024 pixels on the longest edge and converted to JPEGs no larger than 1 MiB.
The provider may resize or crop them further. Images and prompts are sent to
Hugging Face; use material you have permission to upload.

For prompt-only input, it also calls the public
[FLUX.1-schnell Space](https://huggingface.co/spaces/black-forest-labs/FLUX.1-schnell)
at `/gradio_api/call/infer`, using a randomized seed, a 512 × 896 starting image,
and four inference steps. Generated images may be JPEG, PNG or WebP, with a 2 MiB
download limit. Their actual MIME type is preserved when passed to the video
provider. No AI model is installed on the phone or in the Supabase function.

[ZeroGPU quotas](https://huggingface.co/docs/hub/spaces-zerogpu) belong to the
**HF_TOKEN account** and are shared across this backend's users. GPU time differs
from output duration. Six scenes may exhaust the free daily quota, so completing
a reel in one day is not guaranteed. For free-only use, use a free account without
paid credits. The app implements no purchase or billing API.

Stride additionally permits 12 scene attempts and four new reel records per
profile per UTC day. Each reel also permits at most **two AI starting-image
attempts**, including failures. After those attempts, start another reel or
provide your own image in a new reel. Failures count
as attempts. Failed generations require an explicit Continue action; they are
never retried automatically. An ambiguous submission is reconciled against the
server and atomic reservations prevent regenerating an already completed scene.

Video tasks have a 90-second deadline, and starting-image tasks have a 75-second
deadline, to fit Supabase's free 150-second
worker limit. A long queue can fail while the provider continues consuming quota.
Refreshing expires interrupted image or scene jobs after three minutes. Durable external
workers would be needed for reliably handling longer queues. Export uses device
video codecs and can fail on devices with insufficient resources; keep the app
foregrounded during export. A killed export can be started again from saved scenes.

## Storage, access, and verification

`stride-video-inputs` and `stride-generated-videos` remain private. Backend actions
check Auth, feature access, and profile ownership before touching service-role
resources. Image previews and individual clip downloads use one-hour signed links; issued links
remain usable until expiry. Local files that were already downloaded remain on
the device. Logs contain bounded job IDs and error codes, not prompts or tokens.

There is no automatic retention policy. Administratively remove obsolete reels,
scene records, their private images/videos, and orphan files from interrupted
saves in dependency order. Keep Supabase Storage within your plan allowance.
Incomplete image-upload reservations also count toward the new-reel limit.

Build and runtime testing remain with the repository owner. Verify hidden access,
regular Settings saves, uploaded-image rotation, prompt-only generation, removing
an upload, image failure/retry limits, six sequential scenes, quota exhaustion,
app recreation and restart, failed-scene continuation, portrait export duration,
playback, saving through the file picker, and revoked access. If the public Space
changes its API, update the server adapter before use.
