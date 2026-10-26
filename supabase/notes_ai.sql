-- Apply email_auth.sql first, then this file on a DEVELOPMENT project as owner.
-- Deploy functions/notes-ai and set its Gemini secret before using AI tools.
-- Originals are private; provider index cleanup is performed by the Edge Function.
-- Note deletion is restricted while PDF sources exist so remote indexes cannot
-- silently become orphaned through a direct REST delete.
begin;
do $prerequisite$
begin
    if pg_catalog.to_regprocedure('public.resolve_profile()') is null then
        raise exception 'Apply supabase/email_auth.sql first';
    end if;
end;
$prerequisite$;

alter table public.notes add column if not exists source_count integer not null default 0;
create table if not exists public.note_ai_sources (
    id uuid primary key default gen_random_uuid(),
    note_id uuid not null references public.notes(id) on delete restrict,
    profile_id uuid not null references public.users(id) on delete restrict,
    display_name text not null check (length(display_name) between 1 and 160),
    byte_size bigint not null check (byte_size between 1 and 10485760),
    page_count integer not null check (page_count between 1 and 1000),
    storage_path text not null unique,
    status text not null default 'pending' check (status in ('pending','indexing','ready','failed','deleting')),
    store_name text,
    operation_name text,
    lease_until timestamptz,
    error_code text,
    created_at timestamptz not null default now()
);
create index if not exists note_ai_sources_notebook on public.note_ai_sources(note_id, created_at);
create table if not exists public.note_ai_messages (
    id uuid primary key,
    note_id uuid not null references public.notes(id) on delete cascade,
    profile_id uuid not null references public.users(id) on delete restrict,
    question text not null,
    answer text not null,
    source_ids uuid[] not null default '{}',
    citations jsonb not null default '[]',
    created_at timestamptz not null default now()
);
create index if not exists note_ai_messages_notebook on public.note_ai_messages(note_id, created_at);
alter table public.note_ai_messages add column if not exists source_ids uuid[] not null default '{}';
create table if not exists public.note_ai_artifacts (
    id uuid primary key,
    note_id uuid not null references public.notes(id) on delete cascade,
    profile_id uuid not null references public.users(id) on delete restrict,
    kind text not null check (kind in ('summary','study_guide','flashcards','quiz')),
    title text not null,
    content text not null default '',
    data jsonb not null default '{}',
    citations jsonb not null default '[]',
    created_at timestamptz not null default now()
);
create index if not exists note_ai_artifacts_notebook on public.note_ai_artifacts(note_id, created_at);
grant select, insert, update, delete on public.note_ai_sources, public.note_ai_messages, public.note_ai_artifacts to service_role;
create table if not exists stride_auth.note_ai_usage (
    profile_id uuid not null references public.users(id) on delete restrict,
    usage_day date not null,
    uploads integer not null default 0,
    requests integer not null default 0,
    primary key(profile_id, usage_day)
);
create table if not exists stride_auth.note_ai_requests (
    id uuid primary key,
    note_id uuid not null references public.notes(id) on delete cascade,
    profile_id uuid not null references public.users(id) on delete restrict,
    kind text not null,
    status text not null default 'pending' check (status in ('pending','complete','failed')),
    result jsonb,
    created_at timestamptz not null default now()
);
alter table stride_auth.note_ai_usage add column if not exists index_attempts integer not null default 0;
alter table stride_auth.note_ai_usage enable row level security;
alter table stride_auth.note_ai_requests enable row level security;
revoke all on stride_auth.note_ai_usage, stride_auth.note_ai_requests from public, anon, authenticated;
create table if not exists stride_auth.note_ai_locks (
    note_id uuid primary key references public.notes(id) on delete cascade,
    holder uuid not null,
    expires_at timestamptz not null
);
alter table stride_auth.note_ai_locks enable row level security;
revoke all on stride_auth.note_ai_locks from public, anon, authenticated;
create or replace function public.note_ai_take_lock(p_profile uuid, p_note uuid, p_holder uuid)
returns boolean language plpgsql security definer set search_path = '' as $function$
begin
    if not exists(select 1 from public.notes where id = p_note and user_id = p_profile) then raise exception 'NOTE_NOT_FOUND'; end if;
    insert into stride_auth.note_ai_locks(note_id,holder,expires_at) values(p_note,p_holder,now() + interval '3 minutes')
        on conflict(note_id) do update set holder = excluded.holder, expires_at = excluded.expires_at
            where stride_auth.note_ai_locks.expires_at < now();
    return found;
end;
$function$;
create or replace function public.note_ai_release_lock(p_note uuid, p_holder uuid)
returns void language sql security definer set search_path = '' as $function$
    delete from stride_auth.note_ai_locks where note_id = p_note and holder = p_holder;
$function$;
create or replace function public.note_ai_orphan_paths()
returns setof text language sql security definer set search_path = '' as $function$
    select o.name from storage.objects o where o.bucket_id = 'stride-note-pdfs'
        and o.created_at < now() - interval '24 hours'
        and not exists(select 1 from public.note_ai_sources s where s.storage_path = o.name)
        order by o.created_at limit 100;
$function$;
revoke all on function public.note_ai_take_lock(uuid,uuid,uuid), public.note_ai_release_lock(uuid,uuid),
    public.note_ai_orphan_paths() from public, anon, authenticated;
grant execute on function public.note_ai_take_lock(uuid,uuid,uuid), public.note_ai_release_lock(uuid,uuid),
    public.note_ai_orphan_paths() to service_role;

-- Backend-only request ledger lives behind RPC, never an exposed private schema.
create or replace function public.note_ai_reserve_request(p_profile uuid, p_note uuid, p_request uuid, p_kind text)
returns jsonb language plpgsql security definer set search_path = '' as $function$
declare existing stride_auth.note_ai_requests; day_key date := (now() at time zone 'UTC')::date; count_used integer;
begin
    perform 1 from public.users where id = p_profile for update;
    if not exists(select 1 from public.notes where id = p_note and user_id = p_profile) then
        raise exception 'NOTE_NOT_FOUND';
    end if;
    select * into existing from stride_auth.note_ai_requests where id = p_request;
    if found then
        if existing.profile_id <> p_profile or existing.note_id <> p_note or existing.kind <> p_kind then
            raise exception 'REQUEST_CONFLICT';
        end if;
        if existing.status = 'pending' and existing.created_at < now() - interval '3 minutes' then
            update stride_auth.note_ai_requests set status = 'failed' where id = p_request;
            return jsonb_build_object('status','failed');
        end if;
        return jsonb_build_object('status',existing.status,'result',existing.result);
    end if;
    if p_kind not in ('chat','summary','study_guide','flashcards','quiz') then raise exception 'INVALID_ACTION'; end if;
    insert into stride_auth.note_ai_usage(profile_id,usage_day) values(p_profile,day_key) on conflict do nothing;
    select requests into count_used from stride_auth.note_ai_usage where profile_id = p_profile and usage_day = day_key;
    if count_used >= 60 then raise exception 'DAILY_LIMIT'; end if;
    if (select count(*) from stride_auth.note_ai_requests where profile_id = p_profile and status = 'pending'
        and created_at > now() - interval '2 minutes') >= 2 then raise exception 'REQUEST_BUSY'; end if;
    update stride_auth.note_ai_usage set requests = requests + 1 where profile_id = p_profile and usage_day = day_key;
    insert into stride_auth.note_ai_requests(id,note_id,profile_id,kind) values(p_request,p_note,p_profile,p_kind);
    return jsonb_build_object('status','reserved');
end;
$function$;
create or replace function public.note_ai_finish_request(p_profile uuid, p_request uuid, p_result jsonb, p_success boolean)
returns void language sql security definer set search_path = '' as $function$
    update stride_auth.note_ai_requests set status = case when p_success then 'complete' else 'failed' end,
        result = p_result where id = p_request and profile_id = p_profile and status = 'pending';
$function$;

create or replace function public.note_ai_reserve_source(p_profile uuid, p_note uuid, p_name text, p_bytes bigint, p_pages integer)
returns jsonb language plpgsql security definer set search_path = '' as $function$
declare source_id uuid := gen_random_uuid(); row_value public.note_ai_sources; day_key date := (now() at time zone 'UTC')::date;
begin
    perform 1 from public.users where id = p_profile for update;
    if not exists(select 1 from public.notes where id = p_note and user_id = p_profile) then raise exception 'NOTE_NOT_FOUND'; end if;
    -- Reserve maximum-size slots, not caller-declared byte counts, to cap storage abuse.
    if (select count(*) from public.note_ai_sources where profile_id = p_profile) >= 20
        or (select count(*) from public.note_ai_sources where note_id = p_note) >= 10 then raise exception 'SOURCE_LIMIT'; end if;
    insert into stride_auth.note_ai_usage(profile_id,usage_day) values(p_profile,day_key) on conflict do nothing;
    if (select uploads from stride_auth.note_ai_usage where profile_id = p_profile and usage_day = day_key) >= 10 then
        raise exception 'DAILY_LIMIT';
    end if;
    update stride_auth.note_ai_usage set uploads = uploads + 1 where profile_id = p_profile and usage_day = day_key;
    insert into public.note_ai_sources(id,note_id,profile_id,display_name,byte_size,page_count,storage_path)
        values(source_id,p_note,p_profile,p_name,p_bytes,p_pages,p_profile::text || '/' || p_note::text || '/' || source_id::text || '.pdf')
        returning * into row_value;
    return to_jsonb(row_value);
end;
$function$;
create or replace function public.note_ai_claim_source(p_source uuid)
returns boolean language plpgsql security definer set search_path = '' as $function$
declare owner_profile uuid; day_key date := (now() at time zone 'UTC')::date;
begin
    select profile_id into owner_profile from public.note_ai_sources where id = p_source;
    if not found then return false; end if;
    perform 1 from public.users where id = owner_profile for update;
    update public.note_ai_sources set status = 'indexing', lease_until = now() + interval '3 minutes', error_code = null
        where id = p_source and (status in ('pending','failed') or
            (status = 'indexing' and operation_name is null and lease_until < now()));
    if not found then return false; end if;
    insert into stride_auth.note_ai_usage(profile_id,usage_day) values(owner_profile,day_key) on conflict do nothing;
    if (select index_attempts from stride_auth.note_ai_usage where profile_id = owner_profile and usage_day = day_key) >= 20 then
        raise exception 'DAILY_LIMIT';
    end if;
    update stride_auth.note_ai_usage set index_attempts = index_attempts + 1 where profile_id = owner_profile and usage_day = day_key;
    return true;
end;
$function$;
revoke all on function public.note_ai_reserve_request(uuid,uuid,uuid,text),
    public.note_ai_finish_request(uuid,uuid,jsonb,boolean),
    public.note_ai_reserve_source(uuid,uuid,text,bigint,integer), public.note_ai_claim_source(uuid)
    from public, anon, authenticated;
grant execute on function public.note_ai_reserve_request(uuid,uuid,uuid,text),
    public.note_ai_finish_request(uuid,uuid,jsonb,boolean),
    public.note_ai_reserve_source(uuid,uuid,text,bigint,integer), public.note_ai_claim_source(uuid) to service_role;

do $policies$
declare t text; p record;
begin
    foreach t in array array['note_ai_sources','note_ai_messages','note_ai_artifacts'] loop
        execute pg_catalog.format('alter table public.%I enable row level security',t);
        execute pg_catalog.format('revoke all on public.%I from public, anon, authenticated',t);
        execute pg_catalog.format('grant select on public.%I to authenticated',t);
        select polpermissive,polcmd into p from pg_catalog.pg_policy
            where polrelid = pg_catalog.to_regclass('public.' || t) and polname = 'stride_note_ai_read';
        if found then
            if not p.polpermissive or p.polcmd <> 'r' then raise exception 'Incompatible AI read policy on %',t; end if;
            execute pg_catalog.format('alter policy stride_note_ai_read on public.%I to authenticated using (stride_auth.can_access_profile(profile_id))',t);
        else
            execute pg_catalog.format('create policy stride_note_ai_read on public.%I for select to authenticated using (stride_auth.can_access_profile(profile_id))',t);
        end if;
        select polpermissive,polcmd into p from pg_catalog.pg_policy
            where polrelid = pg_catalog.to_regclass('public.' || t) and polname = 'stride_note_ai_guard';
        if found then
            if p.polpermissive or p.polcmd <> '*' then raise exception 'Incompatible AI ownership policy on %',t; end if;
            execute pg_catalog.format('alter policy stride_note_ai_guard on public.%I to authenticated using (stride_auth.can_access_profile(profile_id)) with check (stride_auth.can_access_profile(profile_id))',t);
        else
            execute pg_catalog.format('create policy stride_note_ai_guard on public.%I as restrictive for all to authenticated using (stride_auth.can_access_profile(profile_id)) with check (stride_auth.can_access_profile(profile_id))',t);
        end if;
    end loop;
end;
$policies$;

create or replace function stride_auth.note_source_count()
returns trigger language plpgsql security definer set search_path = '' as $function$
declare target uuid;
begin
    target := case when tg_op = 'DELETE' then old.note_id else new.note_id end;
    update public.notes set source_count = (select count(*) from public.note_ai_sources where note_id = target) where id = target;
    return null;
end;
$function$;
revoke all on function stride_auth.note_source_count() from public, anon, authenticated;
create or replace trigger stride_note_source_count after insert or delete on public.note_ai_sources
    for each row execute function stride_auth.note_source_count();

insert into storage.buckets(id,name,public,file_size_limit,allowed_mime_types)
    values('stride-note-pdfs','stride-note-pdfs',false,10485760,array['application/pdf'])
    on conflict(id) do update set public = false, file_size_limit = excluded.file_size_limit,
        allowed_mime_types = excluded.allowed_mime_types;
-- The app receives short-lived signed URLs after the Edge Function verifies ownership.
-- There are intentionally no anonymous or direct client write policies on this bucket.
create or replace function stride_auth.can_read_note_pdf(object_path text)
returns boolean language sql stable security definer set search_path = '' as $function$
    select exists(select 1 from public.note_ai_sources s where s.storage_path = object_path
        and stride_auth.can_access_profile(s.profile_id));
$function$;
revoke all on function stride_auth.can_read_note_pdf(text) from public, anon, authenticated;
grant execute on function stride_auth.can_read_note_pdf(text) to anon, authenticated;
grant usage on schema stride_auth to anon;
do $storage_guard$
declare p record;
begin
    select polpermissive,polcmd into p from pg_catalog.pg_policy
        where polrelid = 'storage.objects'::regclass and polname = 'stride_note_pdf_guard';
    if found then
        if p.polpermissive or p.polcmd <> '*' then raise exception 'Incompatible PDF bucket guard'; end if;
        alter policy stride_note_pdf_guard on storage.objects to anon,authenticated
            using(bucket_id <> 'stride-note-pdfs' or stride_auth.can_read_note_pdf(name))
            with check(bucket_id <> 'stride-note-pdfs');
    else
        create policy stride_note_pdf_guard on storage.objects as restrictive for all to anon,authenticated
            using(bucket_id <> 'stride-note-pdfs' or stride_auth.can_read_note_pdf(name))
            with check(bucket_id <> 'stride-note-pdfs');
    end if;
end;
$storage_guard$;
-- DELETE has no WITH CHECK evaluation, so it also needs a separate deny guard.
do $storage_delete_guard$
declare p record;
begin
    select polpermissive,polcmd into p from pg_catalog.pg_policy
        where polrelid = 'storage.objects'::regclass and polname = 'stride_note_pdf_delete_guard';
    if found then
        if p.polpermissive or p.polcmd <> 'd' then raise exception 'Incompatible PDF delete guard'; end if;
        alter policy stride_note_pdf_delete_guard on storage.objects to anon,authenticated
            using(bucket_id <> 'stride-note-pdfs');
    else
        create policy stride_note_pdf_delete_guard on storage.objects as restrictive for delete to anon,authenticated
            using(bucket_id <> 'stride-note-pdfs');
    end if;
end;
$storage_delete_guard$;
notify pgrst, 'reload schema';
commit;
