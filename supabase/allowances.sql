-- Apply email_auth.sql first. Run on a development project as database owner.
-- Account-owned manual ledger. Reapplying preserves existing entries and settings.
begin;
do $$ begin
    if pg_catalog.to_regprocedure('stride_auth.can_access_profile(uuid)') is null then
        raise exception 'Apply supabase/email_auth.sql first';
    end if;
end $$;

create table if not exists public.allowance_settings (
    user_id uuid primary key references public.users(id) on delete restrict,
    currency text not null default 'PHP' check(currency in ('PHP','USD','EUR','GBP','SGD','AUD','CAD','CNY','INR')),
    next_payday date not null check(next_payday between date '1900-01-01' and date '2100-12-31'),
    reserved_amount numeric(14,2) not null default 0 check(reserved_amount >= 0),
    revision bigint not null default 0,
    unique(user_id,currency)
);
create table if not exists public.allowance_entries (
    id uuid primary key,
    user_id uuid not null,
    currency text not null,
    kind text not null check(kind in ('income','expense')),
    amount numeric(14,2) not null check(amount > 0),
    category text not null check(length(btrim(category)) between 1 and 60),
    note text not null default '' check(length(note) <= 280),
    occurred_on date not null check(occurred_on between date '1900-01-01' and date '2100-12-31'),
    revision bigint not null default 0,
    created_at timestamptz not null default now(),
    foreign key(user_id,currency) references public.allowance_settings(user_id,currency) on delete restrict on update restrict
);
create index if not exists allowance_entry_history on public.allowance_entries(user_id, occurred_on desc, id desc);

-- Existing transactions remain actual entries. New declarations are explicitly planned.
alter table public.allowance_entries add column if not exists entry_status text not null default 'posted'
    check(entry_status in ('planned','posted'));
alter table public.allowance_entries add column if not exists source text not null default 'manual'
    check(source in ('manual','salary') and (source <> 'salary' or kind = 'income'));

-- Revision filters stop one device silently overwriting changes made on another.
-- Currency cannot be relabeled after transactions have been recorded.
create or replace function public.allowance_revision()
returns trigger language plpgsql set search_path = '' as $$
begin
    if TG_OP = 'INSERT' then new.revision := 0;
    else
        if new.user_id is distinct from old.user_id then raise exception 'ALLOWANCE_OWNER_IMMUTABLE'; end if;
        if TG_TABLE_NAME = 'allowance_settings' then
            if new.currency is distinct from old.currency and exists(
                select 1 from public.allowance_entries where user_id = old.user_id
            ) then raise exception 'ALLOWANCE_CURRENCY_LOCKED'; end if;
        end if;
        new.revision := old.revision + 1;
    end if;
    return new;
end;
$$;
drop trigger if exists allowance_revision on public.allowance_settings;
create trigger allowance_revision before insert or update on public.allowance_settings
    for each row execute function public.allowance_revision();
drop trigger if exists allowance_revision on public.allowance_entries;
create trigger allowance_revision before insert or update on public.allowance_entries
    for each row execute function public.allowance_revision();

alter table public.allowance_settings enable row level security;
alter table public.allowance_entries enable row level security;
revoke all on public.allowance_settings, public.allowance_entries from public, anon, authenticated;
grant select, insert, update on public.allowance_settings to authenticated;
grant select, insert, update, delete on public.allowance_entries to authenticated;
grant select, insert, update, delete on public.allowance_settings, public.allowance_entries to service_role;
drop policy if exists allowance_account_scope on public.allowance_settings;
create policy allowance_account_scope on public.allowance_settings for all to authenticated
    using(stride_auth.can_access_profile(user_id)) with check(stride_auth.can_access_profile(user_id));
drop policy if exists allowance_account_scope on public.allowance_entries;
create policy allowance_account_scope on public.allowance_entries for all to authenticated
    using(stride_auth.can_access_profile(user_id)) with check(stride_auth.can_access_profile(user_id));

-- One stable database snapshot returns exact full-ledger totals and a history page.
-- Numeric values are JSON strings to preserve cents through Android's JSON parser.
create or replace function public.allowance_snapshot(p_profile uuid, p_today date,
    p_before_date date default null, p_before_id uuid default null, p_limit integer default 50)
returns table(settings jsonb, entries jsonb, income_total text, expense_total text, entry_count bigint)
language plpgsql stable security invoker set search_path = '' as $$
begin
    if not stride_auth.can_access_profile(p_profile) then raise exception 'STRIDE_SESSION_EXPIRED' using errcode = '42501'; end if;
    if p_today is null or p_limit not between 1 and 100 or p_limit is null
        or (p_before_date is null) <> (p_before_id is null) then raise exception 'INVALID_REQUEST'; end if;
    return query select
        (select to_jsonb(s) || jsonb_build_object('reserved_amount', s.reserved_amount::text)
            from public.allowance_settings s where s.user_id = p_profile),
        coalesce((select jsonb_agg(to_jsonb(page) || jsonb_build_object('amount',page.amount::text)
                order by page.occurred_on desc, page.id desc)
            from (select e.* from public.allowance_entries e where e.user_id = p_profile and e.occurred_on <= p_today and e.entry_status = 'posted'
                and (p_before_date is null or (e.occurred_on,e.id) < (p_before_date,p_before_id))
                order by e.occurred_on desc, e.id desc limit p_limit) page), '[]'::jsonb),
        coalesce(sum(e.amount) filter(where e.kind = 'income'),0)::text,
        coalesce(sum(e.amount) filter(where e.kind = 'expense'),0)::text,
        count(e.id)
        from public.allowance_entries e where e.user_id = p_profile and e.occurred_on <= p_today and e.entry_status = 'posted';
end;
$$;
revoke all on function public.allowance_snapshot(uuid,date,date,uuid,integer) from public, anon, authenticated;
grant execute on function public.allowance_snapshot(uuid,date,date,uuid,integer) to authenticated;

-- Separate RPC keeps older installed apps compatible and avoids replacing a return type.
-- Actual totals and forecast aggregates cover the full ledger, independently of pagination.
create or replace function public.allowance_forecast_snapshot(p_profile uuid, p_today date, p_forecast date,
    p_before_date date default null, p_before_id uuid default null, p_limit integer default 50)
returns table(settings jsonb, entries jsonb, income_total text, expense_total text,
    planned_income text, planned_expenses text, entry_count bigint)
language plpgsql stable security invoker set search_path = '' as $$
begin
    if not stride_auth.can_access_profile(p_profile) then raise exception 'STRIDE_SESSION_EXPIRED' using errcode = '42501'; end if;
    if p_today is null or p_forecast is null or p_forecast < p_today
        or p_today not between date '1900-01-01' and date '2100-12-31'
        or p_forecast > date '2100-12-31' or p_limit is null or p_limit not between 1 and 100
        or (p_before_date is null) <> (p_before_id is null) then raise exception 'INVALID_REQUEST'; end if;
    return query select
        (select to_jsonb(s) || jsonb_build_object('reserved_amount', s.reserved_amount::text)
            from public.allowance_settings s where s.user_id = p_profile),
        coalesce((select jsonb_agg(to_jsonb(page) || jsonb_build_object('amount',page.amount::text)
                order by page.occurred_on desc, page.id desc)
            from (select e.* from public.allowance_entries e where e.user_id = p_profile
                and (p_before_date is null or (e.occurred_on,e.id) < (p_before_date,p_before_id))
                order by e.occurred_on desc, e.id desc limit p_limit) page), '[]'::jsonb),
        coalesce(sum(e.amount) filter(where e.kind = 'income' and e.entry_status = 'posted' and e.occurred_on <= p_today),0)::text,
        coalesce(sum(e.amount) filter(where e.kind = 'expense' and e.entry_status = 'posted' and e.occurred_on <= p_today),0)::text,
        coalesce(sum(e.amount) filter(where e.kind = 'income' and e.entry_status = 'planned' and e.occurred_on <= p_forecast),0)::text,
        coalesce(sum(e.amount) filter(where e.kind = 'expense' and e.entry_status = 'planned' and e.occurred_on <= p_forecast),0)::text,
        count(e.id)
        from public.allowance_entries e where e.user_id = p_profile;
end;
$$;
revoke all on function public.allowance_forecast_snapshot(uuid,date,date,date,uuid,integer) from public, anon, authenticated;
grant execute on function public.allowance_forecast_snapshot(uuid,date,date,date,uuid,integer) to authenticated;
notify pgrst, 'reload schema';
commit;
