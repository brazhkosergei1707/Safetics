-- Row-Level Security: компанія бачить і змінює лише свої рядки.
-- Контекст встановлює TenantAwareDataSource: set_config('app.company_id' / 'app.user_id', ...).
-- Порожнє або відсутнє значення => null => жодного рядка.

create function app_company_id() returns uuid
    language sql stable as
$$ select nullif(current_setting('app.company_id', true), '')::uuid $$;

create function app_user_id() returns uuid
    language sql stable as
$$ select nullif(current_setting('app.user_id', true), '')::uuid $$;

-- FORCE: політики діють і для власника таблиці (суперкористувач їх усе одно оминає).

-- company: лише своя компанія
alter table company enable row level security;
alter table company force row level security;
create policy tenant_isolation on company
    using (id = app_company_id());

-- role: системні ролі видно всім, власні — лише своїй компанії; створювати можна лише свої
alter table role enable row level security;
alter table role force row level security;
create policy tenant_isolation on role
    using (company_id is null or company_id = app_company_id())
    with check (company_id = app_company_id());

alter table role_permission enable row level security;
alter table role_permission force row level security;
create policy tenant_isolation on role_permission
    using (company_id is null or company_id = app_company_id())
    with check (company_id = app_company_id());

-- membership: членства своєї компанії + власні членства користувача в інших компаніях
-- (щоб показати вибір компанії після входу)
alter table membership enable row level security;
alter table membership force row level security;
create policy tenant_isolation on membership
    using (company_id = app_company_id() or user_id = app_user_id())
    with check (company_id = app_company_id());

alter table audit_log enable row level security;
alter table audit_log force row level security;
create policy tenant_isolation on audit_log
    using (company_id = app_company_id())
    with check (company_id = app_company_id());
