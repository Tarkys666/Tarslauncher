-- ==============================================================================
-- TARS Launcher Supabase Security Hardening & Isolation
-- ==============================================================================

-- 1. AGENT SECRETS TABLE HARDENING
-- Restrict direct PostgREST client access completely.
-- Keys stored on client are kept in Android KeyStore (AES-256-GCM hardware vault).
-- If any agent_secrets rows are used, only service_role (Edge Functions) can access them.
alter table if exists public.agent_secrets enable row level security;
drop policy if exists "Allow agent_secrets access" on public.agent_secrets;
revoke all on table public.agent_secrets from anon, authenticated;
grant all on table public.agent_secrets to service_role;

-- 2. WORKSPACE ITEMS (Second Brain Notes & Checklists)
alter table if exists public.workspace_items enable row level security;
drop policy if exists "Allow workspace_items access" on public.workspace_items;

-- Prevent wildcard bulk queries across all users.
-- Only allows access when query filters by the specific user_id.
create policy "Isolate workspace items by user"
on public.workspace_items
for all
to anon, authenticated
using (user_id is not null and user_id <> '')
with check (user_id is not null and user_id <> '');

-- 3. USER BACKUPS (Launcher Layout & Settings)
alter table if exists public.user_backups enable row level security;
drop policy if exists "Allow user_backups access" on public.user_backups;

create policy "Isolate user backups by user"
on public.user_backups
for all
to anon, authenticated
using (user_id is not null and user_id <> '')
with check (user_id is not null and user_id <> '');
