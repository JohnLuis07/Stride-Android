-- Run email_auth.sql first. Apply on a development project as database owner.
begin;

-- Accept native PostgreSQL arrays cast to text, JSON arrays, and legacy JSON text.
create or replace function stride_auth.video_feature_enabled(value text)
returns boolean language plpgsql immutable set search_path = '' as $$
declare parsed jsonb;
begin
    begin
        parsed := value::jsonb;
    exception when invalid_text_representation then
        -- text[]::text uses {Notes,generatevids}, not JSON's ["Notes","generatevids"].
        -- Let PostgreSQL parse array quoting/escaping before converting it to JSON.
        parsed := pg_catalog.to_jsonb(value::text[]);
    end;
    if jsonb_typeof(parsed) = 'string' then parsed := (parsed #>> '{}')::jsonb; end if;
    return coalesce(jsonb_typeof(parsed) = 'array' and parsed @> '["generatevids"]'::jsonb, false);
exception when others then return false;
end;
$$;

create or replace function public.protect_video_feature()
returns trigger language plpgsql set search_path = '' as $$
begin
    if coalesce(auth.role(), '') in ('anon', 'authenticated')
       or current_user in ('anon', 'authenticated') then
        if TG_OP = 'INSERT' then
            if stride_auth.video_feature_enabled(new.features::text) then
                raise exception 'VIDEO_FEATURE_ADMIN_ONLY' using errcode = '42501';
            end if;
        elsif stride_auth.video_feature_enabled(new.features::text)
              is distinct from stride_auth.video_feature_enabled(old.features::text) then
            raise exception 'VIDEO_FEATURE_ADMIN_ONLY' using errcode = '42501';
        end if;
    end if;
    return new;
end;
$$;
drop trigger if exists protect_video_feature on public.users;
create trigger protect_video_feature before insert or update of features on public.users
    for each row execute function public.protect_video_feature();
grant usage on schema stride_auth to authenticated;
grant execute on function stride_auth.video_feature_enabled(text) to authenticated;

create table if not exists public.video_generation_jobs (
    id uuid primary key,
    profile_id uuid not null references public.users(id) on delete restrict,
    prompt text not null check (length(prompt) between 1 and 1000),
    status text not null default 'queued' check (status in ('queued','saving','complete','failed')),
    output_path text,
    error_code text,
    created_at timestamptz not null default now()
);
create index if not exists video_generation_owner on public.video_generation_jobs(profile_id, created_at desc);
alter table public.video_generation_jobs enable row level security;
revoke all on public.video_generation_jobs from public, anon, authenticated;
grant select, insert, update, delete on public.video_generation_jobs to service_role;

-- Atomic reservation prevents duplicate submissions and concurrent quota races.
create or replace function public.video_generation_reserve(p_profile uuid, p_id uuid, p_prompt text)
returns boolean language plpgsql security definer set search_path = '' as $$
begin
    perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(p_profile::text, 197));
    if not exists(select 1 from public.users where id = p_profile
                  and stride_auth.video_feature_enabled(features::text)) then
        raise exception 'VIDEO_ACCESS_DENIED';
    end if;
    if exists(select 1 from public.video_generation_jobs where id = p_id and profile_id = p_profile) then
        return false;
    end if;
    update public.video_generation_jobs set status = 'failed', error_code = 'VIDEO_TIMEOUT'
        where profile_id = p_profile and status in ('queued','saving')
        and created_at < now() - interval '3 minutes';
    if exists(select 1 from public.video_generation_jobs where profile_id = p_profile and status in ('queued','saving')) then
        raise exception 'VIDEO_ALREADY_RUNNING';
    end if;
    if (select count(*) from public.video_generation_jobs where profile_id = p_profile
        and created_at >= date_trunc('day', now() at time zone 'UTC') at time zone 'UTC') >= 12 then
        raise exception 'VIDEO_DAILY_LIMIT';
    end if;
    insert into public.video_generation_jobs(id, profile_id, prompt) values(p_id, p_profile, p_prompt);
    return true;
end;
$$;
revoke all on function public.video_generation_reserve(uuid, uuid, text) from public, anon, authenticated;
grant execute on function public.video_generation_reserve(uuid, uuid, text) to service_role;

insert into storage.buckets(id, name, public, file_size_limit, allowed_mime_types)
values ('stride-generated-videos', 'stride-generated-videos', false, 41943040, array['video/mp4'])
on conflict(id) do nothing;
-- Reject an incompatible pre-existing public bucket instead of exposing generated media.
do $$ begin
    if exists(select 1 from storage.buckets where id = 'stride-generated-videos' and public) then
        raise exception 'stride-generated-videos must be private';
    end if;
end $$;

create table if not exists public.video_generation_reels (
    id uuid primary key,
    profile_id uuid not null references public.users(id) on delete restrict,
    prompt text not null check(length(prompt) between 1 and 1000),
    input_ready boolean not null default false,
    created_at timestamptz not null default now()
);
create index if not exists video_reel_owner on public.video_generation_reels(profile_id, created_at desc);
alter table public.video_generation_reels add column if not exists input_source text not null default 'upload'
    check(input_source in ('upload','generated'));
alter table public.video_generation_reels add column if not exists input_status text not null default 'waiting'
    check(input_status in ('waiting','generating','ready','failed'));
alter table public.video_generation_reels add column if not exists input_error text;
alter table public.video_generation_reels add column if not exists input_started_at timestamptz;
alter table public.video_generation_reels add column if not exists input_attempts integer not null default 0;
update public.video_generation_reels set input_status = 'ready', input_error = null where input_ready;
alter table public.video_generation_reels enable row level security;
revoke all on public.video_generation_reels from public, anon, authenticated;
grant select, insert, update, delete on public.video_generation_reels to service_role;
alter table public.video_generation_jobs add column if not exists reel_id uuid
    references public.video_generation_reels(id) on delete restrict;
alter table public.video_generation_jobs add column if not exists scene_index integer check(scene_index between 0 and 5);
create index if not exists video_reel_scenes on public.video_generation_jobs(reel_id, scene_index, created_at desc);

create or replace function public.video_reel_create(p_profile uuid, p_id uuid, p_prompt text)
returns boolean language plpgsql security definer set search_path = '' as $$
begin
    perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(p_profile::text, 197));
    if not exists(select 1 from public.users where id = p_profile
        and stride_auth.video_feature_enabled(features::text)) then raise exception 'VIDEO_ACCESS_DENIED'; end if;
    if exists(select 1 from public.video_generation_reels where id = p_id and profile_id = p_profile) then return false; end if;
    if exists(select 1 from public.video_generation_jobs where profile_id = p_profile
        and status in ('queued','saving') and created_at > now() - interval '3 minutes') then
        raise exception 'VIDEO_ALREADY_RUNNING';
    end if;
    if exists(select 1 from public.video_generation_reels where profile_id = p_profile
        and input_status = 'generating' and input_started_at > now() - interval '3 minutes') then
        raise exception 'VIDEO_ALREADY_RUNNING';
    end if;
    if (select count(*) from public.video_generation_reels where profile_id = p_profile
        and created_at >= date_trunc('day', now() at time zone 'UTC') at time zone 'UTC') >= 4 then
        raise exception 'VIDEO_REEL_LIMIT';
    end if;
    insert into public.video_generation_reels(id, profile_id, prompt) values(p_id, p_profile, p_prompt);
    return true;
end;
$$;
revoke all on function public.video_reel_create(uuid,uuid,text) from public, anon, authenticated;
grant execute on function public.video_reel_create(uuid,uuid,text) to service_role;

-- Image attempts are reserved atomically; reconnecting never repeats an active request.
create or replace function public.video_reel_claim_image(p_profile uuid, p_reel uuid)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare r public.video_generation_reels%rowtype;
begin
    perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(p_profile::text, 197));
    if not exists(select 1 from public.users where id = p_profile
        and stride_auth.video_feature_enabled(features::text)) then raise exception 'VIDEO_ACCESS_DENIED'; end if;
    select * into r from public.video_generation_reels where id = p_reel and profile_id = p_profile for update;
    if not found then raise exception 'VIDEO_ACCESS_DENIED'; end if;
    if r.input_ready or (r.input_status = 'generating' and r.input_started_at > now() - interval '3 minutes') then
        return jsonb_build_object('claimed',false,'reel',to_jsonb(r));
    end if;
    if r.input_attempts >= 2 then raise exception 'VIDEO_IMAGE_LIMIT'; end if;
    if exists(select 1 from public.video_generation_reels where profile_id = p_profile and id <> p_reel
        and input_status = 'generating' and input_started_at > now() - interval '3 minutes')
        or exists(select 1 from public.video_generation_jobs where profile_id = p_profile and status in ('queued','saving')
        and created_at > now() - interval '3 minutes') then raise exception 'VIDEO_ALREADY_RUNNING'; end if;
    update public.video_generation_reels set input_source = 'generated', input_status = 'generating',
        input_error = null, input_started_at = now(), input_attempts = input_attempts + 1
        where id = p_reel returning * into r;
    return jsonb_build_object('claimed',true,'reel',to_jsonb(r));
end;
$$;
revoke all on function public.video_reel_claim_image(uuid,uuid) from public, anon, authenticated;
grant execute on function public.video_reel_claim_image(uuid,uuid) to service_role;

create or replace function public.video_generation_reserve(p_profile uuid, p_id uuid, p_prompt text, p_reel uuid, p_scene integer)
returns boolean language plpgsql security definer set search_path = '' as $$
declare expected integer; i integer; reserved boolean;
begin
    perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(p_profile::text, 197));
    if not exists(select 1 from public.video_generation_reels where id = p_reel and profile_id = p_profile
        and input_ready and prompt = p_prompt) then raise exception 'VIDEO_ACCESS_DENIED'; end if;
    if exists(select 1 from public.video_generation_jobs where id = p_id and profile_id = p_profile
        and reel_id = p_reel and scene_index = p_scene) then return false; end if;
    for i in 0..5 loop
        if not exists(select 1 from public.video_generation_jobs where reel_id = p_reel and scene_index = i and status = 'complete') then
            expected := i; exit;
        end if;
    end loop;
    if expected is null or p_scene is distinct from expected then raise exception 'VIDEO_REFRESH_REQUIRED'; end if;
    reserved := public.video_generation_reserve(p_profile, p_id, p_prompt);
    if reserved then
        update public.video_generation_jobs set reel_id = p_reel, scene_index = p_scene where id = p_id;
    end if;
    return reserved;
end;
$$;
revoke all on function public.video_generation_reserve(uuid,uuid,text,uuid,integer) from public, anon, authenticated;
grant execute on function public.video_generation_reserve(uuid,uuid,text,uuid,integer) to service_role;

insert into storage.buckets(id,name,public,file_size_limit,allowed_mime_types)
values('stride-video-inputs','stride-video-inputs',false,2097152,array['image/jpeg','image/png','image/webp'])
on conflict(id) do update set file_size_limit = excluded.file_size_limit, allowed_mime_types = excluded.allowed_mime_types;
do $$ begin
    if exists(select 1 from storage.buckets where id = 'stride-video-inputs' and public) then
        raise exception 'stride-video-inputs must be private';
    end if;
end $$;
commit;
