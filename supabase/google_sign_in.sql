-- Optional Google compatibility, applied AFTER email_auth.sql on a development project.
-- Email/password accounts use the same profile mapping and can access all Notes AI tools.
-- Configure Google Cloud OAuth and Supabase's Google provider only if Google login is desired.
begin;
do $prerequisite$
begin
    if pg_catalog.to_regclass('stride_auth.account_profiles') is null
        or pg_catalog.to_regprocedure('public.resolve_profile()') is null then
        raise exception 'Apply supabase/email_auth.sql first; it preserves established Google mappings';
    end if;
end;
$prerequisite$;

create or replace function public.resolve_google_profile()
returns jsonb language plpgsql security definer set search_path = '' as $function$
begin
    if auth.uid() is null or auth.role() <> 'authenticated' or not exists (
        select 1 from auth.identities i where i.user_id = auth.uid() and i.provider = 'google'
    ) then
        raise exception 'STRIDE_PROFILE_UNAVAILABLE' using errcode = '42501';
    end if;
    return public.resolve_profile();
end;
$function$;
revoke all on function public.resolve_google_profile() from public, anon, authenticated;
grant execute on function public.resolve_google_profile() to authenticated;
create or replace trigger stride_google_profile_email before update of email on public.users
    for each row execute function stride_auth.protect_account_email();
notify pgrst, 'reload schema';
commit;
