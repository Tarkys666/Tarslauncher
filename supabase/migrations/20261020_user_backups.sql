-- ==============================================================================
-- TARS Launcher: Supabase User Backups & Hardened RLS Security Schema
-- ==============================================================================

-- 1. Create user_backups table
create table if not exists public.user_backups (
    user_id text primary key,
    payload jsonb not null,
    updated_at bigint not null
);

-- 2. Payload size check constraint (max 1 MB to prevent DoS/OOM)
alter table public.user_backups drop constraint if exists payload_size_limit;
alter table public.user_backups add constraint payload_size_limit check (octet_length(payload::text) < 1048576);

-- 3. Enable Row Level Security (RLS)
alter table public.user_backups enable row level security;

-- 4. Drop any obsolete/unhardened policies
drop policy if exists "Allow user_backups access" on public.user_backups;
drop policy if exists "Isolate user backups by user" on public.user_backups;
drop policy if exists "Isolate user backups select" on public.user_backups;
drop policy if exists "Isolate user backups insert" on public.user_backups;
drop policy if exists "Isolate user backups update" on public.user_backups;
drop policy if exists "Isolate user backups delete" on public.user_backups;

-- 5. Create user-isolated policies with strict header/auth binding
-- Prevents bulk dumping (SELECT * returns empty unless x-tars-user-id matches)
-- Prevents bulk deletion (DELETE all returns 0 rows affected)
create policy "Isolate user backups select"
on public.user_backups
for select
to anon, authenticated
using (
    (nullif(current_setting('request.headers', true)::json->>'x-tars-user-id', '') = user_id)
    or
    (auth.uid() is not null and auth.uid()::text = user_id)
);

create policy "Isolate user backups insert"
on public.user_backups
for insert
to anon, authenticated
with check (
    (nullif(current_setting('request.headers', true)::json->>'x-tars-user-id', '') = user_id)
    or
    (auth.uid() is not null and auth.uid()::text = user_id)
);

create policy "Isolate user backups update"
on public.user_backups
for update
to anon, authenticated
using (
    (nullif(current_setting('request.headers', true)::json->>'x-tars-user-id', '') = user_id)
    or
    (auth.uid() is not null and auth.uid()::text = user_id)
)
with check (
    (nullif(current_setting('request.headers', true)::json->>'x-tars-user-id', '') = user_id)
    or
    (auth.uid() is not null and auth.uid()::text = user_id)
);

create policy "Isolate user backups delete"
on public.user_backups
for delete
to anon, authenticated
using (
    (nullif(current_setting('request.headers', true)::json->>'x-tars-user-id', '') = user_id)
    or
    (auth.uid() is not null and auth.uid()::text = user_id)
);

-- 6. Grant access permissions
grant select, insert, update, delete on table public.user_backups to anon, authenticated;
grant all on table public.user_backups to service_role;
