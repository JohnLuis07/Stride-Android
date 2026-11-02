-- Apply as database owner to a DEVELOPMENT project before installing the new app.
-- Enable Supabase Email auth and configure the OTP templates in docs/notes-ai-setup.md.
-- This intentionally retires anonymous access to personal application tables.
-- Existing profiles are never linked by email alone. An administrator must verify
-- both the old account and the new confirmed Auth identity before linking them.
-- Existing profile IDs, notes, tasks, and Google mappings are preserved.
begin;

do $preflight$
declare t text;
begin
    foreach t in array array['users','notes','tasks','goals','calendar_events'] loop
        if pg_catalog.to_regclass('public.' || t) is null then
            raise exception 'Missing public.%; review the application schema first', t;
        end if;
    end loop;
end;
$preflight$;

create schema if not exists stride_auth;
revoke all on schema stride_auth from public, anon, authenticated;
grant usage on schema stride_auth to authenticated;
create table if not exists stride_auth.account_profiles (
    auth_user_id uuid primary key references auth.users(id) on delete restrict,
    profile_id uuid not null unique references public.users(id) on delete restrict,
    linked_at timestamptz not null default now()
);
alter table stride_auth.account_profiles enable row level security;
revoke all on stride_auth.account_profiles from public, anon, authenticated;

-- Copy only administrator-established Google mappings, never guesses by email.
do $existing_google$
begin
    if pg_catalog.to_regclass('stride_auth.google_profiles') is not null then
        if exists (
            select 1 from stride_auth.google_profiles g
            join stride_auth.account_profiles a on a.auth_user_id = g.auth_user_id or a.profile_id = g.profile_id
            where a.auth_user_id <> g.auth_user_id or a.profile_id <> g.profile_id
        ) then
            raise exception 'Conflicting account mappings; review before migrating';
        end if;
        insert into stride_auth.account_profiles(auth_user_id, profile_id)
            select auth_user_id, profile_id from stride_auth.google_profiles on conflict do nothing;
    end if;
end;
$existing_google$;

create or replace function stride_auth.can_access_profile(target_profile_id uuid)
returns boolean language sql stable security definer set search_path = '' as $function$
    select (select auth.role()) = 'authenticated' and exists (
        select 1 from stride_auth.account_profiles a join auth.users u on u.id = a.auth_user_id
        where a.profile_id = target_profile_id and a.auth_user_id = (select auth.uid())
            and u.email_confirmed_at is not null
    );
$function$;
revoke all on function stride_auth.can_access_profile(uuid) from public, anon, authenticated;
grant execute on function stride_auth.can_access_profile(uuid) to authenticated;

do $ownership$
declare t text; owner_column text; p record; policy_name text;
begin
    foreach t in array array['users','notes','tasks','goals','calendar_events'] loop
        owner_column := case when t = 'users' then 'id' else 'user_id' end;
        execute pg_catalog.format('alter table public.%I enable row level security', t);
        -- Table and column grants to anonymous callers must both be retired.
        execute pg_catalog.format('revoke all on table public.%I from public, anon', t);
        for p in select a.attname from pg_catalog.pg_attribute a
            where a.attrelid = pg_catalog.to_regclass('public.' || t) and a.attnum > 0 and not a.attisdropped
        loop
            execute pg_catalog.format('revoke select (%I), insert (%I), update (%I), references (%I) on public.%I from public, anon',
                p.attname, p.attname, p.attname, p.attname, t);
        end loop;
        foreach policy_name in array array['stride_account_ownership','stride_account_member'] loop
            select polpermissive, polcmd into p from pg_catalog.pg_policy
                where polrelid = pg_catalog.to_regclass('public.' || t) and polname = policy_name;
            if found then
                if p.polcmd <> '*' or p.polpermissive <> (policy_name = 'stride_account_member') then
                    raise exception 'Incompatible policy % on public.%', policy_name, t;
                end if;
                execute pg_catalog.format('alter policy %I on public.%I to %s using (stride_auth.can_access_profile(%I)) with check (stride_auth.can_access_profile(%I))',
                    policy_name, t, case when policy_name = 'stride_account_ownership' then 'anon, authenticated' else 'authenticated' end,
                    owner_column, owner_column);
            else
                execute pg_catalog.format('create policy %I on public.%I as %s for all to %s using (stride_auth.can_access_profile(%I)) with check (stride_auth.can_access_profile(%I))',
                    policy_name, t, case when policy_name = 'stride_account_member' then 'permissive' else 'restrictive' end,
                    case when policy_name = 'stride_account_ownership' then 'anon, authenticated' else 'authenticated' end,
                    owner_column, owner_column);
            end if;
        end loop;
    end loop;
end;
$ownership$;

grant usage on schema public to authenticated;
grant select, update on public.users to authenticated;
grant select, insert, update, delete on public.notes, public.tasks, public.goals, public.calendar_events to authenticated;

-- Shared provisioning for verified sign-in and the optional confirmation trigger.
-- Kept private: application roles must go through the authenticated resolver.
create or replace function stride_auth.provision_verified_profile(target_auth_user_id uuid)
returns uuid language plpgsql security definer set search_path = '' as $function$
declare profile uuid; verified_email text; display_name text;
begin
    if target_auth_user_id is null then
        raise exception 'STRIDE_SESSION_EXPIRED' using errcode = '42501';
    end if;
    perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(target_auth_user_id::text, 0));
    select u.email, coalesce(nullif(btrim(u.raw_user_meta_data ->> 'full_name'),''),
            nullif(btrim(u.raw_user_meta_data ->> 'name'),''), split_part(u.email,'@',1))
        into verified_email, display_name from auth.users u
        where u.id = target_auth_user_id and u.email_confirmed_at is not null and nullif(btrim(u.email),'') is not null;
    if verified_email is null then
        raise exception 'STRIDE_EMAIL_UNCONFIRMED' using errcode = '42501';
    end if;
    perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(lower(verified_email), 1));
    select a.profile_id into profile from stride_auth.account_profiles a where a.auth_user_id = target_auth_user_id;
    if profile is not null then return profile; end if;

    -- Another signup integration may have inserted the canonical profile first.
    -- Recover only an exact Auth ID AND verified-email match, never email alone.
    select u.id into profile from public.users u
        where u.id = target_auth_user_id and lower(u.email) = lower(verified_email)
        for update;
    if profile is not null then
        if exists (select 1 from stride_auth.account_profiles a where a.profile_id = profile)
            or exists (select 1 from public.users u
                where lower(u.email) = lower(verified_email) and u.id <> profile) then
            return null;
        end if;
        -- Do not overwrite an association established concurrently by an administrator.
        insert into stride_auth.account_profiles(auth_user_id,profile_id)
            values(target_auth_user_id,profile) on conflict do nothing;
        if exists (select 1 from stride_auth.account_profiles a
            where a.auth_user_id = target_auth_user_id and a.profile_id = profile) then
            return profile;
        end if;
        return null;
    end if;
    if exists (select 1 from public.users u where lower(u.email) = lower(verified_email) or u.id = target_auth_user_id) then
        -- A collision is a legacy-account review, never evidence to claim that profile.
        return null;
    end if;
    -- Populate the actual column type: a JSON array becomes text[], json/jsonb,
    -- or JSON text as required by the existing public.users.features definition.
    insert into public.users(id, full_name, email, password, features)
        select p.id, p.full_name, p.email, p.password, p.features
        from pg_catalog.jsonb_populate_record(null::public.users, pg_catalog.jsonb_build_object(
            'id',target_auth_user_id, 'full_name',display_name, 'email',verified_email,
            'password',replace(pg_catalog.gen_random_uuid()::text,'-',''),
            'features',pg_catalog.jsonb_build_array('Todo','Calendar','Notes','Goals','Focus')
        )) as p;
    insert into stride_auth.account_profiles(auth_user_id,profile_id) values(target_auth_user_id,target_auth_user_id);
    return target_auth_user_id;
end;
$function$;
revoke all on function stride_auth.provision_verified_profile(uuid) from public, anon, authenticated, service_role;

create or replace function public.resolve_profile()
returns jsonb language plpgsql security definer set search_path = '' as $function$
declare caller uuid := auth.uid(); profile uuid; verified_email text; signup_name text; result jsonb;
begin
    if caller is null or auth.role() <> 'authenticated' then
        raise exception 'STRIDE_SESSION_EXPIRED' using errcode = '42501';
    end if;
    profile := stride_auth.provision_verified_profile(caller);
    if profile is null then
        raise exception 'STRIDE_ACCOUNT_LINK_REQUIRED';
    end if;
    select u.email, coalesce(nullif(btrim(u.raw_user_meta_data ->> 'full_name'), ''),
            nullif(btrim(u.raw_user_meta_data ->> 'name'), ''))
        into verified_email, signup_name from auth.users u
        where u.id = caller and u.email_confirmed_at is not null;
    if verified_email is null then raise exception 'STRIDE_EMAIL_UNCONFIRMED' using errcode = '42501'; end if;
    update public.users set email = verified_email where id = profile and email is distinct from verified_email;
    -- Repair a missing name only on the verified caller's resolved profile.
    -- Preserve names already saved in Stride, even if Auth metadata differs.
    update public.users set full_name = signup_name
        where id = profile and nullif(btrim(full_name), '') is null and signup_name is not null;
    select jsonb_build_object('id',u.id,'full_name',u.full_name,'email',u.email,
        'features',coalesce(to_jsonb(u.features),'["Todo","Calendar","Notes","Goals","Focus"]'::jsonb))
        into result from public.users u where u.id = profile;
    return result;
end;
$function$;
revoke all on function public.resolve_profile() from public, anon, authenticated;
grant execute on function public.resolve_profile() to authenticated;

-- Keep older Google clients using the same verified profile mapping.
create or replace function public.resolve_google_profile()
returns jsonb language plpgsql security definer set search_path = '' as $function$
begin
    if not exists (select 1 from auth.identities i where i.user_id = auth.uid() and i.provider = 'google') then
        raise exception 'STRIDE_PROFILE_UNAVAILABLE' using errcode = '42501';
    end if;
    return public.resolve_profile();
end;
$function$;
revoke all on function public.resolve_google_profile() from public, anon, authenticated;
grant execute on function public.resolve_google_profile() to authenticated;

create or replace function stride_auth.protect_account_email()
returns trigger language plpgsql security definer set search_path = '' as $function$
begin
    if new.email is distinct from old.email and auth.role() in ('anon','authenticated')
        and not exists (
            select 1 from stride_auth.account_profiles a join auth.users u on u.id = a.auth_user_id
            where a.profile_id = old.id and a.auth_user_id = auth.uid()
                and u.email_confirmed_at is not null and u.email = new.email
        ) then
        raise exception 'STRIDE_EMAIL_MANAGED' using errcode = '42501';
    end if;
    return new;
end;
$function$;
revoke all on function stride_auth.protect_account_email() from public, anon, authenticated;
create or replace trigger stride_account_email before update of email on public.users
    for each row execute function stride_auth.protect_account_email();
do $old_email_guard$
begin
    if exists(select 1 from pg_catalog.pg_trigger where tgrelid = 'public.users'::regclass and tgname = 'stride_google_profile_email') then
        execute 'create or replace trigger stride_google_profile_email before update of email on public.users for each row execute function stride_auth.protect_account_email()';
    end if;
end;
$old_email_guard$;

-- Administrator-only operation, AFTER independently verifying old-account ownership
-- and the new confirmed Auth identity. No app endpoint can invoke this function.
create or replace function stride_auth.link_verified_legacy_profile(auth_identity uuid, legacy_profile uuid)
returns void language plpgsql security invoker set search_path = '' as $function$
begin
    perform 1 from public.users where id = legacy_profile for update;
    if not exists (select 1 from auth.users a join public.users p on lower(p.email) = lower(a.email)
        where a.id = auth_identity and p.id = legacy_profile and a.email_confirmed_at is not null) then
        raise exception 'Confirmed identity and profile do not match';
    end if;
    if exists (select 1 from stride_auth.account_profiles where auth_user_id = auth_identity or profile_id = legacy_profile) then
        raise exception 'Identity or profile is already linked; review it manually';
    end if;
    insert into stride_auth.account_profiles(auth_user_id,profile_id) values(auth_identity,legacy_profile);
    update public.users set password = replace(pg_catalog.gen_random_uuid()::text,'-','') where id = legacy_profile;
end;
$function$;
revoke all on function stride_auth.link_verified_legacy_profile(uuid,uuid) from public, anon, authenticated;

notify pgrst, 'reload schema';
commit;
