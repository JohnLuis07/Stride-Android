-- Apply the updated email_auth.sql first. Install as owner on a DEVELOPMENT project.
-- Only newly verified identities without an existing Stride profile are provisioned.
-- This installs a trigger; it does not backfill or relink existing accounts.
begin;
do $preflight$
begin
    if pg_catalog.to_regclass('stride_auth.account_profiles') is null
        or pg_catalog.to_regprocedure('stride_auth.provision_verified_profile(uuid)') is null
        or pg_catalog.to_regprocedure('public.resolve_profile()') is null then
        raise exception 'Apply the updated supabase/email_auth.sql first';
    end if;
end;
$preflight$;

create or replace function stride_auth.on_verified_auth_account()
returns trigger language plpgsql security definer set search_path = '' as $function$
begin
    if new.email_confirmed_at is not null and nullif(btrim(new.email),'') is not null then
        -- Both rows commit with the Auth transaction. Existing mappings are reused;
        -- an unmapped legacy collision is left for administrator verification.
        perform stride_auth.provision_verified_profile(new.id);
    end if;
    return new;
end;
$function$;
revoke all on function stride_auth.on_verified_auth_account() from public, anon, authenticated, service_role;

-- INSERT covers identities already verified when created (including Google).
-- UPDATE covers email/OTP confirmation after the initial unverified signup.
drop trigger if exists stride_provision_verified_account on auth.users;
create trigger stride_provision_verified_account
    after insert or update of email_confirmed_at, email on auth.users
    for each row
    when (new.email_confirmed_at is not null and new.email is not null)
    execute function stride_auth.on_verified_auth_account();
commit;
