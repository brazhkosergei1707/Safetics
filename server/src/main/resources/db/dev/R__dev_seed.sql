-- Демо-дані для локальної розробки. Підключається лише в профілі dev (application-dev.yml).
-- Пароль обох користувачів: safetics-dev
--
--   +380500000001  Demo Owner    — адмін «Demo Drilling»
--   +380500000002  Multi Company — менеджер у «Demo Drilling», адмін «Agua Perfuração» (вибір компанії)

insert into company (id, name, template_key, enabled_modules)
values ('d0000000-0000-4000-8000-000000000001', 'Demo Drilling', 'exploration-drilling', '{reports,stock,hse}'),
       ('d0000000-0000-4000-8000-000000000002', 'Agua Perfuração', 'exploration-drilling', '{reports,stock,hse}')
on conflict (id) do nothing;

insert into app_user (id, phone, full_name, email, password_hash)
values ('d0000000-0000-4000-8000-0000000000a1', '+380500000001', 'Demo Owner', 'owner@demo.local',
        '{bcrypt}$2b$10$r7UeoPbrPap0uX2LsjBhGuFZ1lyOPUxk3ppxnBja4wWK3JwMU/7Ma'),
       ('d0000000-0000-4000-8000-0000000000a2', '+380500000002', 'Multi Company', 'multi@demo.local',
        '{bcrypt}$2b$10$r7UeoPbrPap0uX2LsjBhGuFZ1lyOPUxk3ppxnBja4wWK3JwMU/7Ma')
on conflict (id) do nothing;

insert into membership (company_id, user_id, role_id)
select v.company_id::uuid, v.user_id::uuid, r.id
from (values ('d0000000-0000-4000-8000-000000000001', 'd0000000-0000-4000-8000-0000000000a1', 'admin'),
             ('d0000000-0000-4000-8000-000000000001', 'd0000000-0000-4000-8000-0000000000a2', 'manager'),
             ('d0000000-0000-4000-8000-000000000002', 'd0000000-0000-4000-8000-0000000000a2', 'admin'))
         as v(company_id, user_id, role_code)
         join role r on r.code = v.role_code and r.company_id is null
on conflict (company_id, user_id) do update set role_id = excluded.role_id;

-- Кілька записів журналу, щоб сторінка журналу змін не була порожньою
insert into audit_log (company_id, user_id, entity, entity_id, action)
select 'd0000000-0000-4000-8000-000000000001', 'd0000000-0000-4000-8000-0000000000a1', 'company',
       'd0000000-0000-4000-8000-000000000001', 'create'
where not exists (select 1 from audit_log where company_id = 'd0000000-0000-4000-8000-000000000001');
