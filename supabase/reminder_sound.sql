-- Apply in the SQL Editor on a DEVELOPMENT project before using the sound switch.
-- Existing records keep audible reminders. No policies or ownership rules change.
begin;

alter table public.tasks add column if not exists reminder_sound boolean not null default true;
alter table public.goals add column if not exists reminder_sound boolean not null default true;

update public.tasks set reminder_sound = true where reminder_sound is null;
update public.goals set reminder_sound = true where reminder_sound is null;
alter table public.tasks alter column reminder_sound set default true,
    alter column reminder_sound set not null;
alter table public.goals alter column reminder_sound set default true,
    alter column reminder_sound set not null;

notify pgrst, 'reload schema';
commit;
