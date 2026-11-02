# Automatic provisioning for new accounts

A new verified account automatically receives both its Stride profile in
`public.users` and its login mapping in `stride_auth.account_profiles`.
These rows are created in the same database transaction. An unverified signup
waits for email/OTP confirmation. A Google identity already confirmed at creation
is provisioned when its Auth row is inserted.

## Apply the update

Run these complete files in order in the development project's Supabase SQL Editor
as database owner:

1. The updated [email_auth.sql](../supabase/email_auth.sql).
2. [account_provisioning.sql](../supabase/account_provisioning.sql).

The first updates the resolver and adds its private provisioning function. The
second installs the confirmation trigger on `auth.users`. No Android changes,
app rebuild, or Edge Function deployment is needed for this update.

Review existing custom Auth triggers before installing. This script replaces only
`stride_provision_verified_account`; it does not disable other triggers. If another
custom trigger already inserts a profile directly, review that integration as an
administrator. If that profile has the exact Auth user ID and verified email, the
resolver can complete its missing mapping without inserting or changing the profile.
Other collisions still require administrator review. Trigger order can still affect
integrations that insert profiles unconditionally; review competing writers.

Auth triggers run inside signup/confirmation transactions. A failure can block
that Auth operation, so verify this migration in development before production.
[Supabase documents this behavior](https://supabase.com/docs/guides/auth/managing-user-data).

## Values created

- `public.users.id` equals the new verified Auth user ID.
- `full_name` uses signup metadata `full_name`, then Google's `name`, then the
  email's local part. The email comes from the verified Auth record.
- Default features are `Todo`, `Calendar`, `Notes`, `Goals`, and `Focus`.
  The typed record conversion supports PostgreSQL arrays and JSON-backed columns.
  `Allowance` can be enabled normally in Settings. `generatevids` remains an
  administrator-managed entitlement and is not granted during signup.
- The legacy `password` column receives a random compatibility value. Actual login
  credentials remain managed by Supabase Auth; no plaintext password is copied.
- The mapping stores the verified Auth user ID and the newly created profile ID.

Repeat sign-ins and confirmation events reuse the existing mapping. They do not
reset names, dashboard preferences, legacy password placeholders, or application
data. Identity and email advisory locks serialize concurrent provisioning requests.
Provisioning helpers cannot be called directly by anonymous or authenticated app
roles, and `stride_auth` must remain outside the Data API's exposed schemas.

The resolver uses the same function, so an already verified Auth identity with no
previous Stride profile is also provisioned on its next successful sign-in.
Installing the trigger does not automatically backfill every existing Auth user.

## Existing profiles

An established mapping is reused. A missing mapping can be recovered only when
the profile ID exactly equals the verified Auth ID, the emails match ignoring
case, no other profile has that email, and no conflicting mapping exists. Only
the mapping is inserted; profile data, password placeholders and preferences
remain unchanged. Repeated sign-in is safe and does not create another profile.

An email-only match, mismatched email, duplicate email, or profile linked to
another identity remains blocked. Sign-in reports `STRIDE_ACCOUNT_LINK_REQUIRED` until an
administrator verifies ownership and uses the existing
[legacy account linking procedure](notes-ai-setup.md#4-preserve-existing-accounts).
This update therefore does not automatically resolve the earlier account-linking
message for an existing legacy profile.

## Repair the confirmed fresh account

First apply the updated `supabase/email_auth.sql` above. Signing in again then
recovers an eligible missing mapping automatically. Alternatively, run the
following targeted repair as database owner after confirming the fresh account's
identity. It is restricted to the Auth ID from the reported diagnostic result.
It refuses a missing profile or mismatched verified email and rolls back if the
resolver cannot establish the exact mapping. It does not modify the profile.

```sql
begin;
do $repair$
declare
    target uuid := 'ab819e7b-774e-4291-9260-5fa2290e62bf';
    repaired uuid;
begin
    if not exists (
        select 1 from auth.users a join public.users p on p.id = a.id
        where a.id = target and a.email_confirmed_at is not null
            and nullif(btrim(a.email),'') is not null
            and lower(p.email) = lower(a.email)
    ) then
        raise exception 'Verified Auth ID and existing profile do not match';
    end if;
    repaired := stride_auth.provision_verified_profile(target);
    if repaired is distinct from target then
        raise exception 'Mapping conflict; review before linking';
    end if;
end;
$repair$;
commit;

select auth_user_id, profile_id
from stride_auth.account_profiles
where auth_user_id = 'ab819e7b-774e-4291-9260-5fa2290e62bf'::uuid;
```

Both returned IDs should be `ab819e7b-774e-4291-9260-5fa2290e62bf`. Sign in
again afterward. No Android rebuild or Edge Function deployment is required.

## Verify after applying

Create a fresh development account, verify its code, and inspect the result with
this read-only query, replacing the email:

```sql
select a.id as auth_user_id,
       a.email_confirmed_at is not null as email_confirmed,
       m.profile_id,
       p.full_name,
       p.email,
       p.features
from auth.users a
left join stride_auth.account_profiles m on m.auth_user_id = a.id
left join public.users p on p.id = m.profile_id
where lower(a.email) = lower('new-account@example.com');
```

After confirmation, the two IDs should match for a newly provisioned account.
Also check an unverified signup, a verified Google signup, repeated login without
preference resets, an established legacy mapping, and an unmapped legacy collision.
For recovery, verify exact-ID/email matches with missing mappings, mismatched
emails, duplicate-email profiles, a profile mapped to another identity, and
repeat sign-ins. Confirm that profile contents and preferences are unchanged.
Build, runtime checks, and live database verification remain with the repository
owner. No signup or live database migration was performed by the coding agent.

## Remove the automatic trigger if needed

As database owner:

```sql
drop trigger if exists stride_provision_verified_account on auth.users;
```

This removes automatic confirmation-time provisioning without deleting profiles
or mappings. The updated resolver still provisions eligible accounts at sign-in.
