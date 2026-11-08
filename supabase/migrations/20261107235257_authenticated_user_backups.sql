-- Replace caller-supplied identity headers with verified Supabase Auth ownership.
-- Legacy google_<email> rows are preserved but cannot be claimed by a client.
create table if not exists public.user_backups (
    user_id text primary key,
    payload jsonb not null,
    updated_at bigint not null,
    constraint payload_size_limit check (octet_length(payload::text) < 1048576)
);

alter table public.user_backups enable row level security;
drop policy if exists "Allow user_backups access" on public.user_backups;
drop policy if exists "Isolate user backups by user" on public.user_backups;
drop policy if exists "Isolate user backups select" on public.user_backups;
drop policy if exists "Isolate user backups insert" on public.user_backups;
drop policy if exists "Isolate user backups update" on public.user_backups;
drop policy if exists "Isolate user backups delete" on public.user_backups;
drop policy if exists "Authenticated users own their backups" on public.user_backups;

create policy "Authenticated users own their backups"
on public.user_backups for all to authenticated
using ((select auth.uid())::text = user_id)
with check ((select auth.uid())::text = user_id);

revoke all on table public.user_backups from anon;
grant usage on schema public to authenticated;
grant select, insert, update, delete on table public.user_backups to authenticated;

-- The dashboard's automatic-RLS event trigger is internal, not a public RPC.
do $$ begin
    if to_regprocedure('public.rls_auto_enable()') is not null then
        revoke execute on function public.rls_auto_enable() from public, anon, authenticated;
    end if;
end $$;
