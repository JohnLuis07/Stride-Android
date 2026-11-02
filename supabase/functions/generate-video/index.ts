// Native Android API. Auth, feature access, quotas and ownership are checked server-side.
declare const EdgeRuntime: { waitUntil(task: Promise<unknown>): void };
type Row = Record<string, any>;
const BASE = (Deno.env.get("SUPABASE_URL") || "").replace(/\/$/, "");
const SERVICE = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") || "";
const ANON = Deno.env.get("SUPABASE_ANON_KEY") || "";
const PROVIDER = "https://zerogpu-aoti-wan2-2-fp8da-aoti-faster.hf.space";
const IMAGE_PROVIDER = "https://black-forest-labs-flux-1-schnell.hf.space";
const BUCKET = "stride-generated-videos";
const INPUT_BUCKET = "stride-video-inputs";
const MAX_IMAGE = 1048576;
const MAX_GENERATED_IMAGE = 2097152;
const MAX_VIDEO = 41943040;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
class Failure extends Error {
    constructor(public code: string, public status = 400) { super(code); }
}
function response(body: unknown, status = 200): Response {
    return Response.json(body, {status, headers: {"cache-control": "no-store"}});
}
function features(value: unknown): boolean {
    try {
        if (typeof value === "string") value = JSON.parse(value);
        return Array.isArray(value) && value.includes("generatevids");
    } catch { return false; }
}
async function limited(response: Response, limit: number): Promise<Uint8Array> {
    if (Number(response.headers.get("content-length")) > limit) {
        await response.body?.cancel(); throw new Failure("VIDEO_PROVIDER_ERROR", 502);
    }
    const reader = response.body?.getReader();
    if (!reader) throw new Failure("VIDEO_PROVIDER_ERROR", 502);
    const chunks: Uint8Array[] = [];
    let size = 0;
    try {
        while (true) {
            const {done, value} = await reader.read();
            if (done) break;
            size += value.length;
            if (size > limit) throw new Failure("VIDEO_PROVIDER_ERROR", 502);
            chunks.push(value);
        }
    } finally { await reader.cancel().catch(() => {}); }
    const all = new Uint8Array(size);
    let offset = 0;
    for (const chunk of chunks) { all.set(chunk, offset); offset += chunk.length; }
    return all;
}
async function db(path: string, method = "GET", body?: unknown, token = SERVICE): Promise<any> {
    const result = await fetch(BASE + "/" + path, {
        method, headers: {apikey: token === SERVICE ? SERVICE : ANON,
            authorization: `Bearer ${token}`, "content-type": "application/json", prefer: "return=representation"},
        body: body === undefined ? undefined : JSON.stringify(body),
        redirect: "error", signal: AbortSignal.timeout(10000),
    });
    const raw = new TextDecoder().decode(await limited(result, 262144));
    if (!result.ok) {
        // Only bounded machine codes cross the API; never echo database/provider details.
        for (const code of ["VIDEO_ACCESS_DENIED", "VIDEO_ALREADY_RUNNING", "VIDEO_DAILY_LIMIT", "VIDEO_REEL_LIMIT", "VIDEO_REFRESH_REQUIRED", "VIDEO_IMAGE_LIMIT"]) {
            if (raw.includes(code)) throw new Failure(code, code === "VIDEO_ACCESS_DENIED" ? 403 : 429);
        }
        throw new Failure(result.status === 401 ? "VIDEO_ACCESS_DENIED" : "VIDEO_SETUP_REQUIRED", 503);
    }
    return raw ? JSON.parse(raw) : null;
}
async function provider(path: string, init: RequestInit, signal: AbortSignal, origin = PROVIDER): Promise<Response> {
    const token = Deno.env.get("HF_TOKEN");
    if (!token) throw new Failure("VIDEO_SETUP_REQUIRED", 503);
    const result = await fetch(origin + path, {...init, headers: {
        authorization: `Bearer ${token}`, ...init.headers,
    }, redirect: "error", signal});
    if (!result.ok) {
        console.warn(JSON.stringify({event: "video_provider_error", operation: origin === IMAGE_PROVIDER ? "image" : "video", status: result.status}));
        await result.body?.cancel();
        if (result.status === 429) throw new Failure("VIDEO_QUOTA", 429);
        if (result.status === 401 || result.status === 403) throw new Failure("VIDEO_SETUP_REQUIRED", 503);
        throw new Failure("VIDEO_PROVIDER_ERROR", 502);
    }
    return result;
}
function errorCode(error: unknown): string {
    if (error instanceof Failure) return error.code;
    if (error instanceof Error && ["TimeoutError", "AbortError"].includes(error.name)) return "VIDEO_TIMEOUT";
    return "VIDEO_PROVIDER_ERROR";
}
async function patch(job: Row, changes: Row): Promise<void> {
    await db(`rest/v1/video_generation_jobs?id=eq.${job.id}&profile_id=eq.${job.profile_id}&status=in.(queued,saving)`, "PATCH", changes);
}
async function resultUrl(stream: Response, signal: AbortSignal, origin = PROVIDER): Promise<string> {
    const reader = stream.body?.getReader();
    if (!reader) throw new Failure("VIDEO_PROVIDER_ERROR", 502);
    const decoder = new TextDecoder();
    let pending = "";
    try {
        while (!signal.aborted) {
            const next = await reader.read();
            if (next.done) break;
            pending += decoder.decode(next.value, {stream: true}).replace(/\r/g, "");
            if (pending.length > 262144) throw new Failure("VIDEO_PROVIDER_ERROR", 502);
            let end: number;
            while ((end = pending.indexOf("\n\n")) >= 0) {
                const frame = pending.slice(0, end);
                pending = pending.slice(end + 2);
                const lines = frame.split("\n");
                const event = lines.find(line => line.startsWith("event:"))?.slice(6).trim();
                const data = lines.filter(line => line.startsWith("data:")).map(line => line.slice(5).trim()).join("\n");
                if (event === "error") {
                    throw new Failure(/quota|GPU.*limit|exceeded|duration/i.test(data) ? "VIDEO_QUOTA" : "VIDEO_PROVIDER_ERROR", 502);
                }
                if (event === "complete") {
                    const output = JSON.parse(data)?.[0];
                    const video = output?.video || output;
                    const location = video?.url || (video?.path ? `/gradio_api/file=${video.path}` : "");
                    if (!location) throw new Failure("VIDEO_PROVIDER_ERROR", 502);
                    const url = new URL(location, origin);
                    if (url.origin !== origin || !(url.pathname.startsWith("/gradio_api/file=") || url.pathname.startsWith("/file="))) {
                        throw new Failure("VIDEO_PROVIDER_ERROR", 502);
                    }
                    return url.pathname + url.search;
                }
            }
        }
        throw new Failure(signal.aborted ? "VIDEO_TIMEOUT" : "VIDEO_PROVIDER_ERROR", 502);
    } finally { await reader.cancel().catch(() => {}); }
}
async function run(job: Row, image: Uint8Array): Promise<void> {
    // Leaves time for cleanup inside the free Edge worker's 150-second wall limit.
    const signal = AbortSignal.timeout(90000);
    try {
        const form = new FormData();
        const mime = imageMime(image);
        form.append("files", new Blob([image], {type: mime}), "input." + (mime === "image/jpeg" ? "jpg" : mime.split("/")[1]));
        const upload = await provider("/gradio_api/upload", {method: "POST", body: form}, signal);
        const paths = JSON.parse(new TextDecoder().decode(await limited(upload, 8192)));
        if (!Array.isArray(paths) || typeof paths[0] !== "string") throw new Failure("VIDEO_PROVIDER_ERROR", 502);
        const submitted = await provider("/gradio_api/call/generate_video", {
            method: "POST", headers: {"content-type": "application/json"},
            body: JSON.stringify({data: [{path: paths[0], meta: {_type: "gradio.FileData"}},
                job.prompt, 4, "blurry, distorted, subtitles, watermark, static", 5, 1, 1, 42, true]}),
        }, signal);
        const event = JSON.parse(new TextDecoder().decode(await limited(submitted, 8192))).event_id;
        if (typeof event !== "string" || !/^[a-zA-Z0-9_-]{1,128}$/.test(event)) throw new Failure("VIDEO_PROVIDER_ERROR", 502);
        const stream = await provider(`/gradio_api/call/generate_video/${event}`, {}, signal);
        const path = await resultUrl(stream, signal);
        await patch(job, {status: "saving"});
        const video = await provider(path, {}, signal);
        const bytes = await limited(video, MAX_VIDEO);
        if (bytes.length < 12 || new TextDecoder().decode(bytes.slice(4, 8)) !== "ftyp") {
            throw new Failure("VIDEO_PROVIDER_ERROR", 502);
        }
        const savedPath = `${job.profile_id}/${job.id}.mp4`;
        const storage = await fetch(`${BASE}/storage/v1/object/${BUCKET}/${savedPath}`, {
            method: "POST", headers: {apikey: SERVICE, authorization: `Bearer ${SERVICE}`, "content-type": "video/mp4"},
            body: bytes, redirect: "error", signal,
        });
        if (!storage.ok) throw new Failure("VIDEO_STORAGE_ERROR", 503);
        await storage.body?.cancel();
        await patch(job, {status: "complete", output_path: savedPath, error_code: null});
        console.info(JSON.stringify({event: "video_finished", job_id: job.id, code: "OK"}));
    } catch (error) {
        const code = errorCode(error);
        await patch(job, {status: "failed", error_code: code}).catch(() => {});
        console.warn(JSON.stringify({event: "video_finished", job_id: job.id, code}));
        // Preserve uploaded media if a database acknowledgement was lost after commit.
        // Administrators can remove orphan files after reviewing terminal job records.
    }
}
async function jobs(profile: string, reel?: string): Promise<Row[]> {
    await db(`rest/v1/video_generation_jobs?profile_id=eq.${profile}&status=in.(queued,saving)&created_at=lt.${encodeURIComponent(new Date(Date.now() - 180000).toISOString())}`,
        "PATCH", {status: "failed", error_code: "VIDEO_TIMEOUT"});
    const fields = "id,status,error_code,created_at,scene_index,reel_id";
    let rows = await db(`rest/v1/video_generation_jobs?profile_id=eq.${profile}&select=${fields}`
        + (reel ? `&reel_id=eq.${reel}` : "") + "&order=created_at.desc&limit=100");
    if (reel) {
        const completed = await db(`rest/v1/video_generation_jobs?profile_id=eq.${profile}&reel_id=eq.${reel}&status=eq.complete&select=${fields}&limit=6`);
        const seen = new Set(rows.map((row: Row) => row.id));
        rows = rows.concat(completed.filter((row: Row) => !seen.has(row.id)));
    }
    return rows.map((job: Row) => ({id: job.id, prompt: job.prompt, status: job.status,
        error_code: job.error_code, created_at: job.created_at, scene_index: job.scene_index, reel_id: job.reel_id}));
}
async function reelOwner(profile: string, reel: unknown): Promise<Row> {
    if (typeof reel !== "string" || !UUID.test(reel)) throw new Failure("INVALID_REQUEST");
    const rows = await db(`rest/v1/video_generation_reels?id=eq.${reel}&profile_id=eq.${profile}&limit=1`);
    if (!rows[0]) throw new Failure("VIDEO_ACCESS_DENIED", 404);
    return rows[0];
}
function imageMime(image: Uint8Array): string {
    if (image.length >= 4 && image[0] === 255 && image[1] === 216 && image[2] === 255) return "image/jpeg";
    if (image.length >= 8 && image.slice(0,8).every((byte, index) => byte === [137,80,78,71,13,10,26,10][index])) return "image/png";
    if (image.length >= 12 && new TextDecoder().decode(image.slice(0,4)) === "RIFF"
        && new TextDecoder().decode(image.slice(8,12)) === "WEBP") return "image/webp";
    throw new Failure("VIDEO_IMAGE_ERROR", 502);
}
function inputPath(reel: Row): string { return `${reel.profile_id}/${reel.id}.jpg`; }
async function patchInput(reel: Row, changes: Row, leased = false): Promise<void> {
    await db(`rest/v1/video_generation_reels?id=eq.${reel.id}&profile_id=eq.${reel.profile_id}&input_ready=eq.false`
        + (leased ? `&input_status=eq.generating&input_started_at=eq.${encodeURIComponent(reel.input_started_at)}` : ""), "PATCH", changes);
}
async function saveInput(reel: Row, image: Uint8Array, leased = false, signal = AbortSignal.timeout(10000)): Promise<void> {
    const saved = await fetch(`${BASE}/storage/v1/object/${INPUT_BUCKET}/${inputPath(reel)}`, {
        method: "POST", headers: {apikey: SERVICE, authorization: `Bearer ${SERVICE}`, "content-type": imageMime(image)},
        body: image, signal, redirect: "error",
    });
    if (!saved.ok && saved.status !== 409) { await saved.body?.cancel(); throw new Failure("VIDEO_STORAGE_ERROR", 503); }
    await saved.body?.cancel();
    await patchInput(reel, {input_ready: true, input_status: "ready", input_error: null}, leased);
}
async function makeInput(reel: Row): Promise<void> {
    const signal = AbortSignal.timeout(75000);
    try {
        // Recover a committed image upload before issuing another provider request.
        const existing = await fetch(`${BASE}/storage/v1/object/${INPUT_BUCKET}/${inputPath(reel)}`, {
            headers: {apikey: SERVICE, authorization: `Bearer ${SERVICE}`}, signal, redirect: "error",
        });
        if (existing.ok) {
            const bytes = await limited(existing, MAX_GENERATED_IMAGE);
            imageMime(bytes);
            await patchInput(reel, {input_ready: true, input_status: "ready", input_error: null}, true);
            return;
        }
        await existing.body?.cancel();
        if (existing.status !== 404 && existing.status !== 400) throw new Failure("VIDEO_STORAGE_ERROR", 503);
        const submitted = await provider("/gradio_api/call/infer", {
            method: "POST", headers: {"content-type": "application/json"},
            body: JSON.stringify({data: [reel.prompt, 0, true, 512, 896, 4]}),
        }, signal, IMAGE_PROVIDER);
        const event = JSON.parse(new TextDecoder().decode(await limited(submitted, 8192))).event_id;
        if (typeof event !== "string" || !/^[a-zA-Z0-9_-]{1,128}$/.test(event)) throw new Failure("VIDEO_IMAGE_ERROR", 502);
        const stream = await provider(`/gradio_api/call/infer/${event}`, {}, signal, IMAGE_PROVIDER);
        const path = await resultUrl(stream, signal, IMAGE_PROVIDER);
        const image = await provider(path, {}, signal, IMAGE_PROVIDER);
        await saveInput(reel, await limited(image, MAX_GENERATED_IMAGE), true, signal);
        console.info(JSON.stringify({event: "video_input_finished", reel_id: reel.id, code: "OK"}));
    } catch (error) {
        const code = errorCode(error);
        await patchInput(reel, {input_status: "failed", input_error: code}, true).catch(() => {});
        console.warn(JSON.stringify({event: "video_input_finished", reel_id: reel.id, code}));
    }
}
async function requestInput(profile: string, reelId: string): Promise<Row> {
    const claim = await db("rest/v1/rpc/video_reel_claim_image", "POST", {p_profile: profile, p_reel: reelId});
    if (claim?.claimed === true) EdgeRuntime.waitUntil(makeInput(claim.reel));
    return claim.reel;
}
async function storageLink(bucket: string, path: string): Promise<string> {
    const signed = await db(`storage/v1/object/sign/${bucket}/${path}`, "POST", {expiresIn: 3600});
    const signedPath = signed.signedURL;
    if (typeof signedPath !== "string") throw new Failure("VIDEO_SETUP_REQUIRED", 503);
    const url = new URL(signedPath.startsWith("/object/")
        ? `${BASE}/storage/v1${signedPath}` : signedPath, `${BASE}/storage/v1/`);
    if (url.origin !== new URL(BASE).origin) throw new Failure("VIDEO_SETUP_REQUIRED", 503);
    return url.href;
}
async function runScene(job: Row, reel: Row): Promise<void> {
    try {
        const input = await fetch(`${BASE}/storage/v1/object/${INPUT_BUCKET}/${job.profile_id}/${reel.id}.jpg`, {
            headers: {apikey: SERVICE, authorization: `Bearer ${SERVICE}`}, redirect: "error", signal: AbortSignal.timeout(10000),
        });
        if (!input.ok) { await input.body?.cancel(); throw new Failure("VIDEO_STORAGE_ERROR", 503); }
        await run(job, await limited(input, MAX_GENERATED_IMAGE));
    } catch (error) { await patch(job, {status: "failed", error_code: errorCode(error)}).catch(() => {}); }
}
Deno.serve(async request => {
    try {
        if (request.method !== "POST") return response({error: "INVALID_ACTION"}, 405);
        if (!BASE || !SERVICE || !ANON) throw new Failure("VIDEO_SETUP_REQUIRED", 503);
        const authorization = request.headers.get("authorization") || "";
        if (!authorization.startsWith("Bearer ")) throw new Failure("VIDEO_ACCESS_DENIED", 401);
        const token = authorization.slice(7);
        const identity = await db("auth/v1/user", "GET", undefined, token);
        if (!identity.id || !identity.email_confirmed_at) throw new Failure("VIDEO_ACCESS_DENIED", 401);
        const profile = await db("rest/v1/rpc/resolve_profile", "POST", {}, token);
        if (!UUID.test(profile?.id) || !features(profile.features)) throw new Failure("VIDEO_ACCESS_DENIED", 403);
        const bytes = await limited(new Response(request.body), 1450000);
        let body: Row;
        try { body = JSON.parse(new TextDecoder().decode(bytes)); }
        catch { throw new Failure("INVALID_REQUEST"); }
        if (!body || Array.isArray(body) || typeof body !== "object") throw new Failure("INVALID_REQUEST");
        if (body.action === "list") {
            await db(`rest/v1/video_generation_reels?profile_id=eq.${profile.id}&input_ready=eq.false&input_status=eq.generating`
                + `&input_started_at=lt.${encodeURIComponent(new Date(Date.now() - 180000).toISOString())}`,
                "PATCH", {input_status: "failed", input_error: "VIDEO_TIMEOUT"});
            let reel: Row | null = null;
            if (body.reel_id) reel = await reelOwner(profile.id, body.reel_id);
            else {
                const rows = await db(`rest/v1/video_generation_reels?profile_id=eq.${profile.id}&or=(input_ready.eq.true,input_source.eq.generated)&order=created_at.desc&limit=1`);
                reel = rows[0] || null;
            }
            return response({reel, clips: reel ? await jobs(profile.id, reel.id) : []});
        }
        if (body.action === "retry_image") {
            if (!Deno.env.get("HF_TOKEN")) throw new Failure("VIDEO_SETUP_REQUIRED", 503);
            const reel = await reelOwner(profile.id, body.reel_id);
            const current = await requestInput(profile.id, reel.id);
            return response({id: reel.id, accepted: true, input_status: current.input_status,
                input_ready: current.input_ready, input_source: current.input_source});
        }
        if (body.action === "input_link") {
            const reel = await reelOwner(profile.id, body.reel_id);
            if (!reel.input_ready) throw new Failure("VIDEO_REFRESH_REQUIRED", 409);
            return response({url: await storageLink(INPUT_BUCKET, inputPath(reel))});
        }
        if (body.action === "scene") {
            if (!UUID.test(body.id) || !Number.isInteger(body.scene_index) || body.scene_index < 0 || body.scene_index > 5) {
                throw new Failure("INVALID_REQUEST");
            }
            if (!Deno.env.get("HF_TOKEN")) throw new Failure("VIDEO_SETUP_REQUIRED", 503);
            const reel = await reelOwner(profile.id, body.reel_id);
            const reserved = await db("rest/v1/rpc/video_generation_reserve", "POST", {
                p_profile: profile.id, p_id: body.id, p_prompt: reel.prompt, p_reel: reel.id, p_scene: body.scene_index,
            });
            if (reserved === true) EdgeRuntime.waitUntil(runScene({id: body.id, profile_id: profile.id, prompt: reel.prompt}, reel));
            return response({id: body.id, accepted: true});
        }
        if (body.action === "link") {
            if (!UUID.test(body.id)) throw new Failure("INVALID_REQUEST");
            const rows = await db(`rest/v1/video_generation_jobs?id=eq.${body.id}&profile_id=eq.${profile.id}&status=eq.complete&limit=1`);
            const path = rows[0]?.output_path;
            if (typeof path !== "string" || path !== `${profile.id}/${body.id}.mp4`) throw new Failure("VIDEO_ACCESS_DENIED", 404);
            return response({url: await storageLink(BUCKET, path)});
        }
        if (!["start", "create_reel"].includes(body.action) || !UUID.test(body.id) || typeof body.prompt !== "string"
            || body.prompt.trim().length < 1 || body.prompt.length > 1000) throw new Failure("INVALID_REQUEST");
        if (!Deno.env.get("HF_TOKEN")) throw new Failure("VIDEO_SETUP_REQUIRED", 503);
        let image: Uint8Array | undefined;
        if (body.image !== undefined && body.image !== null) {
            if (typeof body.image !== "string" || body.image.length > 1400000) throw new Failure("INVALID_REQUEST");
            try { image = Uint8Array.from(atob(body.image), character => character.charCodeAt(0)); }
            catch { throw new Failure("INVALID_REQUEST"); }
            if (image.length > MAX_IMAGE || image.length < 4 || imageMime(image) !== "image/jpeg") {
                throw new Failure("INVALID_REQUEST");
            }
        }
        if (body.action === "create_reel") {
            await db("rest/v1/rpc/video_reel_create", "POST", {p_profile: profile.id, p_id: body.id, p_prompt: body.prompt.trim()});
            const reel = await reelOwner(profile.id, body.id);
            if (!image) {
                const current = await requestInput(profile.id, reel.id);
                return response({id: reel.id, accepted: true, input_status: current.input_status,
                    input_ready: current.input_ready, input_source: current.input_source});
            }
            if (!reel.input_ready) {
                if (reel.input_source !== "upload") throw new Failure("VIDEO_REFRESH_REQUIRED", 409);
                await saveInput(reel, image);
            }
            return response({id: reel.id, accepted: true, input_status: "ready", input_ready: true, input_source: reel.input_source});
        }
        if (!image) throw new Failure("INVALID_REQUEST");
        const reserved = await db("rest/v1/rpc/video_generation_reserve", "POST", {
            p_profile: profile.id, p_id: body.id, p_prompt: body.prompt.trim(),
        });
        if (reserved === true) EdgeRuntime.waitUntil(run({id: body.id, profile_id: profile.id, prompt: body.prompt.trim()}, image));
        return response({id: body.id, accepted: true});
    } catch (error) {
        const code = errorCode(error);
        console.warn(JSON.stringify({event: "video_request_error", code}));
        return response({error: code}, error instanceof Failure ? error.status : 503);
    }
});
