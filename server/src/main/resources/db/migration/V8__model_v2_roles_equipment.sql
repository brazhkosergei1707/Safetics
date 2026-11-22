-- Перехід на узгоджену модель (контекст, «Бізнес-логіка платформи», розділи 1 і 3).
--
-- 1. Свердловина — не обладнання, а послуга (з'явиться в кроці 3). Тип drill_hole і його записи видаляються.
-- 2. Тип обладнання має категорію: основне (виконує послуги) або допоміжне.
-- 3. Ролі: admin — верхня роль компанії; owner → director («Керівник»: як менеджер, але на всіх проєктах);
--    client_rep («Представник замовника»: перевіряє рапорти) лишається, додається client_manager.
-- 4. Останнього активного адміна компанії не можна понизити, вимкнути чи видалити.
--
-- Міграція виконується власником схеми (поза RLS) і зачіпає всі компанії. Бойових даних ще немає.

-- ---------------------------------------------------------------- обладнання

delete from asset
where asset_type_id in (select id from asset_type where code = 'drill_hole');

delete from asset_type
where code = 'drill_hole';

update asset_type
set allowed_child_types = allowed_child_types - 'drill_hole'
where allowed_child_types ? 'drill_hole';

alter table asset_type
    add column category text not null default 'auxiliary'
        check (category in ('primary', 'auxiliary'));

comment on column asset_type.category is
    'primary — основне обладнання, виконує послуги (бурова); auxiliary — допоміжне (авто, табір, ємність ПММ)';

update asset_type
set category = 'primary'
where code = 'drill_rig';

-- ---------------------------------------------------------------- дозволи

insert into permission (code, description) values
    ('project.access_all', 'Доступ до всіх проєктів компанії (без окремого призначення)'),
    ('report.review', 'Змінні рапорти: перевірка представником замовника (прийняти, повернути, виправити)'),
    ('monthly_report.approve', 'Місячний звіт: затвердження замовником');

-- report.approve_client → report.review
insert into role_permission (role_id, permission_code, company_id)
select role_id, 'report.review', company_id
from role_permission
where permission_code = 'report.approve_client';

delete from role_permission where permission_code in ('report.approve_client', 'report.approve_site');
delete from permission where code in ('report.approve_client', 'report.approve_site');

-- ---------------------------------------------------------------- ролі

-- Керівник: дозволи менеджера + усі проєкти. Попередні дозволи owner прибираються.
update role
set code = 'director', name = 'Керівник'
where code = 'owner' and company_id is null;

delete from role_permission
where role_id = (select id from role where code = 'director' and company_id is null);

insert into role_permission (role_id, permission_code)
select (select id from role where code = 'director' and company_id is null), rp.permission_code
from role_permission rp
where rp.role_id = (select id from role where code = 'manager' and company_id is null);

insert into role_permission (role_id, permission_code)
select id, 'project.access_all'
from role
where company_id is null and code in ('admin', 'director');

-- Адмін — усе, крім дій від імені замовника
insert into role_permission (role_id, permission_code)
select r.id, p.code
from role r
         cross join permission p
where r.company_id is null
  and r.code = 'admin'
  and p.code not in ('report.review', 'monthly_report.approve')
on conflict do nothing;

update role set name = 'Адмін' where code = 'admin' and company_id is null;

-- Ролі замовника (портал). Обмеження проєктами свого замовника — разом із таблицями проєктів.
-- client_rep — будь-хто з боку замовника, хто перевіряє рапорти (геолог, інженер, супервайзер замовника).
update role
set name = 'Представник замовника'
where code = 'client_rep' and company_id is null;

insert into role (company_id, code, name, is_system)
values (null, 'client_manager', 'Менеджер замовника', true);

delete from role_permission
where role_id in (select id from role where company_id is null and code in ('client_rep', 'client_manager'));

insert into role_permission (role_id, permission_code)
select r.id, v.permission_code
from (values ('client_rep', 'report.view'),
             ('client_rep', 'report.review'),
             ('client_manager', 'report.view'),
             ('client_manager', 'monthly_report.approve')) as v(role_code, permission_code)
         join role r on r.code = v.role_code and r.company_id is null;

-- ---------------------------------------------------------------- останній адмін

create function protect_last_admin() returns trigger
    language plpgsql as
$$
declare
    admin_role uuid;
    was_admin  boolean;
    still_admin boolean;
begin
    select id into admin_role from role where code = 'admin' and company_id is null;
    was_admin := old.role_id = admin_role and old.status = 'active';
    still_admin := tg_op = 'UPDATE' and new.role_id = admin_role and new.status = 'active'
                   and new.company_id = old.company_id;

    if was_admin and not still_admin and not exists (
        select 1
        from membership m
        where m.company_id = old.company_id
          and m.id <> old.id
          and m.role_id = admin_role
          and m.status = 'active'
    ) then
        raise exception 'Company % must keep at least one active admin', old.company_id
            using errcode = 'check_violation', constraint = 'last_admin';
    end if;

    return case when tg_op = 'DELETE' then old else new end;
end
$$;

create trigger membership_protect_last_admin
    before update or delete
    on membership
    for each row
execute function protect_last_admin();
