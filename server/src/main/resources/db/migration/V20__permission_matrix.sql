-- Крок 5д: матриця прав (safetics-context.md, розділ «Матриця прав»).
-- Роль «Головний менеджер», відповідальний менеджер проєкту, області видимості: обладнання й
-- замовники — у межах своїх проєктів, задачі й зміни виконавця — свої (👤).

-- ---------------------------------------------------------------- дозволи й ролі

insert into permission (code, description) values
    ('client.view_all', 'Усі замовники компанії (інакше — лише замовники своїх проєктів); створення замовників'),
    ('asset.view_all',  'Усе обладнання компанії (інакше — лише призначене на свої проєкти)'),
    ('project.create',  'Створення й закриття проєктів, відповідальний менеджер'),
    ('task.view_all',   'Усі задачі й зміни своїх проєктів (інакше — лише свої)')
on conflict (code) do nothing;

insert into role (company_id, code, name, is_system)
values (null, 'head_manager', 'Головний менеджер', true);

-- Головний менеджер — операційні права керівника на всіх проєктах
insert into role_permission (role_id, permission_code)
select hm.id, rp.permission_code
from role hm
         join role d on d.code = 'director' and d.company_id is null
         join role_permission rp on rp.role_id = d.id
where hm.code = 'head_manager' and hm.company_id is null;

insert into role_permission (role_id, permission_code)
select r.id, v.permission_code
from (values ('admin', 'client.view_all'), ('admin', 'asset.view_all'), ('admin', 'project.create'), ('admin', 'task.view_all'),
             ('director', 'client.view_all'), ('director', 'asset.view_all'), ('director', 'project.create'),
             ('director', 'task.view_all'),
             ('head_manager', 'client.view_all'), ('head_manager', 'asset.view_all'), ('head_manager', 'project.create'),
             ('head_manager', 'task.view_all'),
             ('manager', 'asset.view_all'), ('manager', 'task.view_all')) as v(role_code, permission_code)
         join role r on r.code = v.role_code and r.company_id is null
on conflict do nothing;

-- Виконавець: без замовників, без зміни обладнання, оплат і списань (списання — через рапорт)
delete from role_permission
where role_id in (select id from role where code = 'worker' and company_id is null)
  and permission_code in ('client.view', 'client.edit', 'asset.edit', 'payment.accept', 'stock.write_off');

-- ---------------------------------------------------------------- відповідальний менеджер

alter table project add column manager_id uuid references app_user (id);

-- ---------------------------------------------------------------- функції видимості

-- Користувач — супервайзер проєкту сьогодні (призначення з посадою, у якої project_role = supervisor)
create function app_is_project_supervisor(p_project_id uuid) returns boolean
    language sql stable as
$$
select exists (select 1
               from personnel p
                        join personnel_assignment pa on pa.personnel_id = p.id
                        join position pos on pos.id = pa.position_id
               where p.user_id = app_user_id()
                 and pa.project_id = p_project_id
                 and pos.project_role = 'supervisor'
                 and pa.start_date <= current_date
                 and (pa.end_date is null or pa.end_date >= current_date))
$$;

-- Усі задачі / зміни проєкту: task.view_all, супервайзер проєкту або система
create function app_sees_all_work(p_project_id uuid) returns boolean
    language sql stable as
$$
select (select app_system()) or (select app_has_permission('task.view_all')) or app_is_project_supervisor(p_project_id)
$$;

-- Користувач у бригаді зміни
create function app_in_crew(p_shift_id uuid) returns boolean
    language sql stable as
$$
select exists (select 1
               from work_shift_crew c
                        join personnel p on p.id = c.personnel_id
               where c.shift_id = p_shift_id
                 and p.user_id = app_user_id())
$$;

-- Користувач працював на обладнанні (у бригаді його зміни за останні 30 днів)
create function app_works_on_asset(p_asset_id uuid) returns boolean
    language sql stable as
$$
select exists (select 1
               from work_shift_crew c
                        join personnel p on p.id = c.personnel_id
                        join work_shift s on s.id = c.shift_id
               where s.asset_id = p_asset_id
                 and p.user_id = app_user_id()
                 and s.shift_date >= current_date - 30)
$$;

-- Обладнання: asset.view_all або призначене (саме чи батьківське) на видимий проєкт
create function app_sees_asset(p_asset_id uuid, p_parent_id uuid) returns boolean
    language sql stable as
$$
select (select app_system())
    or (select app_has_permission('asset.view_all'))
    or exists (select 1
               from equipment_assignment ea
               where ea.asset_id in (p_asset_id, p_parent_id)
                 and app_can_see_project(ea.project_id))
$$;

-- ---------------------------------------------------------------- бригада: проєкт у рядку

-- Бригада бачиться за проєктом (без посилання на work_shift — інакше політики зациклюються)
alter table work_shift_crew add column project_id uuid;
update work_shift_crew c set project_id = s.project_id from work_shift s where s.id = c.shift_id;
alter table work_shift_crew alter column project_id set not null;

create function work_shift_crew_project() returns trigger
    language plpgsql as
$$
begin
    select s.project_id into new.project_id from work_shift s where s.id = new.shift_id;
    return new;
end
$$;

create trigger work_shift_crew_project
    before insert or update of shift_id on work_shift_crew
    for each row execute function work_shift_crew_project();

drop policy tenant_isolation on work_shift_crew;
create policy tenant_isolation on work_shift_crew
    using (company_id = app_company_id() and app_can_see_project(project_id))
    with check (company_id = app_company_id() and app_can_see_project(project_id));

-- ---------------------------------------------------------------- політики

-- Зміни: усі (task.view_all, супервайзер) або ті, де користувач у бригаді
drop policy tenant_isolation on work_shift;
create policy tenant_isolation on work_shift
    using (company_id = app_company_id() and app_can_see_project(project_id)
           and ((select app_has_permission('task.view')) or (select app_system()))
           and (app_sees_all_work(project_id) or app_in_crew(id)))
    with check (company_id = app_company_id() and app_can_see_project(project_id)
                and ((select app_has_permission('task.view')) or (select app_system())));

-- Задачі: усі (task.view_all, супервайзер) або свої: виконавець / автор, задачі змін у бригаді,
-- задачі по обладнанню, на якому працює
drop policy tenant_isolation on task;
create policy tenant_isolation on task
    using (company_id = app_company_id() and app_can_see_project(project_id)
           and ((select app_has_permission('task.view')) or (select app_system()))
           and (app_sees_all_work(project_id)
               or assignee_id = app_user_id()
               or author_id = app_user_id()
               or (work_shift_id is not null and app_in_crew(work_shift_id))
               or (asset_id is not null and app_works_on_asset(asset_id))))
    with check (company_id = app_company_id() and app_can_see_project(project_id)
                and ((select app_has_permission('task.view')) or (select app_system())));

-- Обладнання: усе (asset.view_all) або на своїх проєктах
drop policy tenant_isolation on asset;
create policy tenant_isolation on asset
    using (company_id = app_company_id() and app_sees_asset(id, parent_id))
    with check (company_id = app_company_id()
                and ((select app_has_permission('asset.view_all')) or (select app_system())
                    or app_sees_asset(id, parent_id)));

-- Замовники: усі (client.view_all) або замовники видимих проєктів і послуг (портал — свій)
drop policy tenant_isolation on customer;
create policy tenant_isolation on customer
    using (company_id = app_company_id()
           and ((select app_has_permission('client.view_all')) or (select app_system())
               or exists (select 1 from project p where p.customer_id = customer.id)
               or exists (select 1 from service s where s.customer_id = customer.id)))
    with check (company_id = app_company_id()
                and ((select app_has_permission('client.view_all')) or (select app_system())
                    or exists (select 1 from project p where p.customer_id = customer.id)
                    or exists (select 1 from service s where s.customer_id = customer.id)));
