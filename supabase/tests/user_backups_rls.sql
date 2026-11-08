-- Run as postgres in the SQL Editor. Test data is rolled back.
begin;
insert into public.user_backups (user_id, payload, updated_at) values
('11111111-1111-4111-8111-111111111111', '{"probe":1}', 0),
('22222222-2222-4222-8222-222222222222', '{"probe":2}', 0);
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"11111111-1111-4111-8111-111111111111","role":"authenticated"}', true);
do $$ declare denied boolean := false;
begin
    if (select count(*) from public.user_backups) <> 1 then
        raise exception 'RLS leaked another backup';
    end if;
    update public.user_backups set payload = '{"probe":3}'
    where user_id = '11111111-1111-4111-8111-111111111111';
    if not found then raise exception 'Own backup update failed'; end if;
    begin
        insert into public.user_backups values ('33333333-3333-4333-8333-333333333333', '{}', 0);
    exception when insufficient_privilege then denied := true;
    end;
    if not denied then raise exception 'Cross-user insert allowed'; end if;
    denied := false;
    begin
        update public.user_backups set user_id = '33333333-3333-4333-8333-333333333333';
    exception when insufficient_privilege then denied := true;
    end;
    if not denied then raise exception 'Owner reassignment allowed'; end if;
    if has_table_privilege('anon', 'public.user_backups', 'SELECT') then
        raise exception 'Anonymous access allowed';
    end if;
end $$;
rollback;
select 'PASS: backup ownership enforced; test rows rolled back' as result;
