-- Task/goal deadline timing for Stride. Run this ENTIRE file as database owner
-- in the DEVELOPMENT project's Supabase SQL Editor before using the new app.
-- Review the live schema, existing triggers, and permissions first.
-- No historical completion times or timezones are guessed/backfilled.
-- Reapplying this script preserves existing timing data and access policies.
--
-- New/edited deadlines save the device timezone and optional declared time.
-- The server uses that exact local time; date-only deadlines end at the start
-- of the following day in that zone, including daylight saving.
-- Run the updated script even if the earlier date-only version was applied.
-- Legacy unfinished items use the device's local day for display until saved.
-- The app also supplies a missing timezone on status/progress changes.
-- Legacy clients can still write; if they complete an item without a recorded
-- deadline timezone, the app shows that the completion deadline is unavailable.
-- Completion timestamps and deadline snapshots are owned by this trigger.
-- Reopening clears them; subsequent completion records a new result.
-- The snapshot survives title, progress, and deadline edits while still complete.
-- Existing table grants and row-level policies remain responsible for ownership.

begin;

do $preflight$
declare
    table_name text;
    column_name text;
    required record;
    relation regclass;
    actual_type oid;
begin
    foreach table_name in array array['tasks', 'goals'] loop
        relation := pg_catalog.to_regclass(pg_catalog.format('public.%I', table_name));
        if relation is null or not exists (
            select 1 from pg_catalog.pg_class c where c.oid = relation and c.relkind in ('r', 'p')
        ) then
            raise exception 'Expected public.% to be a table; review the live schema', table_name;
        end if;
    end loop;

    for required in
        select * from (values
            ('tasks', 'due_date'), ('tasks', 'status'),
            ('goals', 'target_date'), ('goals', 'target_value'), ('goals', 'current_value')
        ) as specification(table_name, column_name)
    loop
        if not exists (
            select 1 from pg_catalog.pg_attribute a
            where a.attrelid = pg_catalog.to_regclass('public.' || required.table_name)
                and a.attname = required.column_name and a.attnum > 0 and not a.attisdropped
        ) then
            raise exception 'Missing public.%.%; adapt the migration to the live schema',
                required.table_name, required.column_name;
        end if;
    end loop;

    foreach table_name in array array['tasks', 'goals'] loop
        column_name := case when table_name = 'tasks' then 'due_date' else 'target_date' end;
        select a.atttypid into actual_type from pg_catalog.pg_attribute a
        where a.attrelid = pg_catalog.to_regclass('public.' || table_name)
            and a.attname = column_name and not a.attisdropped;
        if actual_type not in ('date'::regtype, 'text'::regtype, 'varchar'::regtype) then
            raise exception 'Expected public.%.% to be date or text; review the live schema', table_name, column_name;
        end if;
    end loop;

    foreach column_name in array array['target_value', 'current_value'] loop
        select a.atttypid into actual_type from pg_catalog.pg_attribute a
        where a.attrelid = 'public.goals'::regclass and a.attname = column_name and not a.attisdropped;
        if actual_type not in ('int2'::regtype, 'int4'::regtype, 'int8'::regtype,
                'numeric'::regtype, 'float4'::regtype, 'float8'::regtype) then
            raise exception 'Expected public.goals.% to be numeric; review the live schema', column_name;
        end if;
    end loop;
end;
$preflight$;

alter table public.tasks
    add column if not exists deadline_timezone text,
    add column if not exists deadline_time time without time zone,
    add column if not exists deadline_at timestamptz,
    add column if not exists completed_at timestamptz,
    add column if not exists completed_deadline_at timestamptz;

alter table public.goals
    add column if not exists deadline_timezone text,
    add column if not exists deadline_time time without time zone,
    add column if not exists deadline_at timestamptz,
    add column if not exists completed_at timestamptz,
    add column if not exists completed_deadline_at timestamptz;

-- A same-named existing column must have the expected nullable type.
do $timing_columns$
declare
    table_name text;
    expected record;
begin
    foreach table_name in array array['tasks', 'goals'] loop
        for expected in
            select * from (values
                ('deadline_timezone', 'text'::regtype),
                ('deadline_time', 'time'::regtype),
                ('deadline_at', 'timestamptz'::regtype),
                ('completed_at', 'timestamptz'::regtype),
                ('completed_deadline_at', 'timestamptz'::regtype)
            ) as specification(column_name, type_id)
        loop
            if not exists (
                select 1 from pg_catalog.pg_attribute a
                where a.attrelid = pg_catalog.to_regclass('public.' || table_name)
                    and a.attname = expected.column_name and a.atttypid = expected.type_id
                    and not a.attnotnull and not a.attisdropped
            ) then
                raise exception 'public.%.% must be nullable %; adapt the migration first',
                    table_name, expected.column_name, expected.type_id;
            end if;
        end loop;
    end loop;
end;
$timing_columns$;

create or replace function public.stride_set_deadline_timing()
returns trigger
language plpgsql
security invoker
set search_path = ''
set datestyle = 'ISO, YMD'
as $function$
declare
    new_values jsonb;
    old_values jsonb := '{}'::jsonb;
    date_column text;
    date_key text;
    old_date_key text;
    deadline_date date;
    deadline_local timestamp without time zone;
    new_complete boolean;
    old_complete boolean;
begin
    if tg_table_schema <> 'public' or tg_table_name not in ('tasks', 'goals')
            or tg_op not in ('INSERT', 'UPDATE') then
        raise exception 'Unsupported deadline timing trigger target';
    end if;

    new_values := pg_catalog.to_jsonb(new);
    if tg_op = 'UPDATE' then
        old_values := pg_catalog.to_jsonb(old);
    end if;
    date_column := case when tg_table_name = 'tasks' then 'due_date' else 'target_date' end;
    date_key := nullif(pg_catalog.btrim(new_values ->> date_column), '');
    old_date_key := nullif(pg_catalog.btrim(old_values ->> date_column), '');

    if tg_table_name = 'tasks' then
        new_complete := coalesce(new_values ->> 'status' = 'done', false);
        old_complete := coalesce(old_values ->> 'status' = 'done', false);
    else
        new_complete := coalesce(
            (new_values ->> 'target_value')::numeric > 0
            and (new_values ->> 'current_value')::numeric >= (new_values ->> 'target_value')::numeric
            and new_values ->> 'target_value' not in ('NaN', 'Infinity', '-Infinity')
            and new_values ->> 'current_value' not in ('NaN', 'Infinity', '-Infinity'), false);
        old_complete := coalesce(
            (old_values ->> 'target_value')::numeric > 0
            and (old_values ->> 'current_value')::numeric >= (old_values ->> 'target_value')::numeric
            and old_values ->> 'target_value' not in ('NaN', 'Infinity', '-Infinity')
            and old_values ->> 'current_value' not in ('NaN', 'Infinity', '-Infinity'), false);
    end if;

    new.deadline_timezone := nullif(pg_catalog.btrim(new.deadline_timezone), '');
    if date_key is null then
        new.deadline_timezone := null;
        new.deadline_time := null;
        new.deadline_at := null;
    else
        deadline_date := date_key::date;
        if not pg_catalog.isfinite(deadline_date) then
            raise exception 'Deadline must be a finite calendar date' using errcode = '22007';
        end if;
        if new.deadline_time = time '24:00:00' then
            raise exception 'Use a time before 24:00, or leave it empty for end of day' using errcode = '22007';
        end if;
        deadline_local := case when new.deadline_time is null
            then (deadline_date + 1)::timestamp without time zone
            else deadline_date + new.deadline_time end;
        if new.deadline_timezone is null then
            new.deadline_at := null;
        elsif tg_op = 'UPDATE' and date_key is not distinct from old_date_key
                and new.deadline_time is not distinct from old.deadline_time
                and new.deadline_timezone is not distinct from old.deadline_timezone
                and old.deadline_at is not null then
            -- Ignore direct timestamp writes when the actual deadline did not change.
            new.deadline_at := old.deadline_at;
        elsif exists (
            select 1 from pg_catalog.pg_timezone_names z where z.name = new.deadline_timezone
        ) then
            new.deadline_at := deadline_local at time zone new.deadline_timezone;
            -- Reject a selected clock time skipped by daylight saving instead
            -- of silently moving it. Ambiguous fall-back times use PostgreSQL's
            -- standard-time interpretation consistently across clients.
            if new.deadline_time is not null
                    and (new.deadline_at at time zone new.deadline_timezone) is distinct from deadline_local then
                raise exception 'The selected deadline time does not exist in its time zone' using errcode = '22007';
            end if;
        elsif new.deadline_timezone ~ '^GMT[+-]([01][0-9]|2[0-3]):[0-5][0-9]$' then
            -- Android can return fixed GMT offsets. Interval zones use ISO signs;
            -- interpreting GMT+08:00 as a PostgreSQL text zone would reverse it.
            new.deadline_at := deadline_local
                at time zone (pg_catalog.substr(new.deadline_timezone, 4)::interval);
        else
            raise exception 'Unsupported deadline timezone: %', new.deadline_timezone using errcode = '22023';
        end if;
    end if;

    if not new_complete then
        new.completed_at := null;
        new.completed_deadline_at := null;
    elsif not old_complete then
        new.completed_at := pg_catalog.clock_timestamp();
        new.completed_deadline_at := new.deadline_at;
    else
        -- Preserve the original result, including unknown legacy completions.
        new.completed_at := old.completed_at;
        new.completed_deadline_at := old.completed_deadline_at;
    end if;
    return new;
end;
$function$;

revoke all on function public.stride_set_deadline_timing() from public, anon, authenticated;

create or replace trigger zz_stride_deadline_timing
    before insert or update on public.tasks
    for each row execute function public.stride_set_deadline_timing();

create or replace trigger zz_stride_deadline_timing
    before insert or update on public.goals
    for each row execute function public.stride_set_deadline_timing();

notify pgrst, 'reload schema';
commit;
