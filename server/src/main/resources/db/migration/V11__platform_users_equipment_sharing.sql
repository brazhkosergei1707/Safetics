-- Крок 2в: суперадмін платформи, журнал платформи, спільне допоміжне обладнання.

-- ---------------------------------------------------------------- суперадмін

-- Роль платформи, а не членство в компанії. Видається лише SQL-командою (див. server/README.md):
--   update app_user set is_platform_admin = true where phone = '+380...';
alter table app_user add column is_platform_admin boolean not null default false;

-- Чи поточний користувач (з перевіреного JWT) — адмін платформи.
-- У політиках викликається як (select app_is_platform_admin()) — один раз на запит.
create function app_is_platform_admin() returns boolean
    language sql stable as
$$
select coalesce((select u.is_platform_admin from app_user u where u.id = app_user_id()), false)
$$;

-- Суперадмін бачить і змінює компанії (створити, призупинити) та членства (перший адмін,
-- список адмінів). Дані компаній (проєкти, рапорти, обладнання…) йому не відкриваються.
create policy company_platform_select on company
    for select
    using ((select app_is_platform_admin()));
create policy company_platform_update on company
    for update
    using ((select app_is_platform_admin()))
    with check ((select app_is_platform_admin()));

create policy membership_platform_select on membership
    for select
    using ((select app_is_platform_admin()));

-- Журнал дій платформи: лише додавання
create table platform_audit_log (
    id         bigint generated always as identity primary key,
    user_id    uuid        not null references app_user (id),
    company_id uuid references company (id),
    action     text        not null,
    details    jsonb,
    created_at timestamptz not null default now()
);
create index platform_audit_log_created_idx on platform_audit_log (created_at);

alter table platform_audit_log enable row level security;
alter table platform_audit_log force row level security;
create policy platform_only on platform_audit_log
    using ((select app_is_platform_admin()))
    with check ((select app_is_platform_admin()) and user_id = app_user_id());

revoke update, delete, truncate on platform_audit_log from ${appdbuser};

-- ---------------------------------------------------------------- спільне допоміжне обладнання

-- Основне обладнання (бурова) — лише на одному проєкті одночасно. Допоміжне (водовозка, вантажівка)
-- може бути на кількох проєктах, але не двічі на тому самому проєкті з перекриттям дат.
-- exclusive — категорія типу в момент призначення (основне = true); заповнює тригер.
alter table equipment_assignment add column exclusive boolean not null default true;

create function equipment_assignment_set_exclusive() returns trigger
    language plpgsql as
$$
begin
    if tg_op = 'INSERT' or new.asset_id <> old.asset_id then
        select t.category = 'primary'
        into new.exclusive
        from asset a
                 join asset_type t on t.id = a.asset_type_id
        where a.id = new.asset_id;
    end if;
    return new;
end
$$;

create trigger equipment_assignment_exclusive
    before insert or update
    on equipment_assignment
    for each row
execute function equipment_assignment_set_exclusive();

update equipment_assignment ea
set exclusive = (t.category = 'primary')
from asset a
         join asset_type t on t.id = a.asset_type_id
where a.id = ea.asset_id;

alter table equipment_assignment drop constraint equipment_assignment_no_overlap;
alter table equipment_assignment
    add constraint equipment_assignment_no_overlap exclude using gist (
        asset_id with =,
        daterange(start_date, end_date, '[]') with &&) where (exclusive);
alter table equipment_assignment
    add constraint equipment_assignment_same_project_no_overlap exclude using gist (
        asset_id with =,
        project_id with =,
        daterange(start_date, end_date, '[]') with &&);

-- Хто де стоїть, видно в межах компанії (щоб показати «зайнято до …» при призначенні, навіть якщо
-- інший проєкт користувачу не видно — назва такого проєкту не показується). Змінювати призначення
-- можна лише на видимих проєктах.
drop policy tenant_isolation on equipment_assignment;
create policy equipment_assignment_select on equipment_assignment
    for select
    using (company_id = app_company_id());
create policy equipment_assignment_insert on equipment_assignment
    for insert
    with check (company_id = app_company_id() and app_can_see_project(project_id));
create policy equipment_assignment_update on equipment_assignment
    for update
    using (company_id = app_company_id() and app_can_see_project(project_id))
    with check (company_id = app_company_id() and app_can_see_project(project_id));
create policy equipment_assignment_delete on equipment_assignment
    for delete
    using (company_id = app_company_id() and app_can_see_project(project_id));
