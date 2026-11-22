-- Демо-дані для локальної розробки. Підключається лише в профілі dev (application-dev.yml).
-- Пароль усіх користувачів: safetics-dev
--
--   +380500000001  Demo Owner    — адмін «Demo Drilling»
--   +380500000002  Multi Company — менеджер у «Demo Drilling» (доступ лише до проєкту VALE-CRJ),
--                                  адмін «Agua Perfuração» (вибір компанії)
--   +380500000003  Ana Souza     — представник замовника Vale Exploration у «Demo Drilling» (портал)

insert into company (id, name, template_key, enabled_modules)
values ('d0000000-0000-4000-8000-000000000001', 'Demo Drilling', 'exploration-drilling', '{reports,stock,hse}'),
       ('d0000000-0000-4000-8000-000000000002', 'Agua Perfuração', 'exploration-drilling', '{reports,stock,hse}')
on conflict (id) do nothing;

insert into app_user (id, phone, full_name, email, password_hash)
values ('d0000000-0000-4000-8000-0000000000a1', '+380500000001', 'Demo Owner', 'owner@demo.local',
        '{bcrypt}$2b$10$r7UeoPbrPap0uX2LsjBhGuFZ1lyOPUxk3ppxnBja4wWK3JwMU/7Ma'),
       ('d0000000-0000-4000-8000-0000000000a2', '+380500000002', 'Multi Company', 'multi@demo.local',
        '{bcrypt}$2b$10$r7UeoPbrPap0uX2LsjBhGuFZ1lyOPUxk3ppxnBja4wWK3JwMU/7Ma'),
       ('d0000000-0000-4000-8000-0000000000a3', '+380500000003', 'Ana Souza', 'ana@vale.local',
        '{bcrypt}$2b$10$r7UeoPbrPap0uX2LsjBhGuFZ1lyOPUxk3ppxnBja4wWK3JwMU/7Ma')
on conflict (id) do nothing;

insert into membership (company_id, user_id, role_id)
select v.company_id::uuid, v.user_id::uuid, r.id
from (values ('d0000000-0000-4000-8000-000000000001', 'd0000000-0000-4000-8000-0000000000a1', 'admin'),
             ('d0000000-0000-4000-8000-000000000001', 'd0000000-0000-4000-8000-0000000000a2', 'manager'),
             ('d0000000-0000-4000-8000-000000000002', 'd0000000-0000-4000-8000-0000000000a2', 'admin'),
             ('d0000000-0000-4000-8000-000000000001', 'd0000000-0000-4000-8000-0000000000a3', 'client_rep'))
         as v(company_id, user_id, role_code)
         join role r on r.code = v.role_code and r.company_id is null
on conflict (company_id, user_id) do update set role_id = excluded.role_id;

-- Кілька записів журналу, щоб сторінка журналу змін не була порожньою
insert into audit_log (company_id, user_id, entity, entity_id, action)
select 'd0000000-0000-4000-8000-000000000001', 'd0000000-0000-4000-8000-0000000000a1', 'company',
       'd0000000-0000-4000-8000-000000000001', 'create'
where not exists (select 1 from audit_log where company_id = 'd0000000-0000-4000-8000-000000000001');

-- ---------------------------------------------------------------- замовники і проєкти (Demo Drilling)

insert into customer (id, company_id, name, legal_code, country, email)
values ('d0000000-0000-4000-8000-0000000000c1', 'd0000000-0000-4000-8000-000000000001', 'Vale Exploration',
        '33.592.510/0001-54', 'BR', 'exploration@vale.local'),
       ('d0000000-0000-4000-8000-0000000000c2', 'd0000000-0000-4000-8000-000000000001', 'Taziazet Gold',
        null, 'MR', null)
on conflict (id) do nothing;

insert into customer_contact (id, company_id, customer_id, full_name, job_title, phone, email, user_id)
values ('d0000000-0000-4000-8000-0000000000d1', 'd0000000-0000-4000-8000-000000000001',
        'd0000000-0000-4000-8000-0000000000c1', 'Ana Souza', 'Geóloga', '+380500000003', 'ana@vale.local',
        'd0000000-0000-4000-8000-0000000000a3'),
       ('d0000000-0000-4000-8000-0000000000d2', 'd0000000-0000-4000-8000-000000000001',
        'd0000000-0000-4000-8000-0000000000c1', 'Carlos Lima', 'Gerente de contrato', null, 'carlos@vale.local',
        null)
on conflict (id) do nothing;

insert into project (id, company_id, customer_id, code, name, location, country, time_zone, start_date)
values ('d0000000-0000-4000-8000-0000000000e1', 'd0000000-0000-4000-8000-000000000001',
        'd0000000-0000-4000-8000-0000000000c1', 'VALE-CRJ', 'Carajás exploration 2026', 'Parauapebas, PA', 'BR',
        'America/Belem', '2026-10-01'),
       ('d0000000-0000-4000-8000-0000000000e2', 'd0000000-0000-4000-8000-000000000001',
        'd0000000-0000-4000-8000-0000000000c2', 'TZG-01', 'Tasiast North infill', 'Inchiri', 'MR',
        'Africa/Nouakchott', '2026-11-01')
on conflict (id) do nothing;

insert into shift_schedule (company_id, project_id, valid_from, shifts)
values ('d0000000-0000-4000-8000-000000000001', 'd0000000-0000-4000-8000-0000000000e1', '2026-10-01',
        '[{"code": "day", "name": "Day", "start": "07:00", "durationMinutes": 720},
          {"code": "night", "name": "Night", "start": "19:00", "durationMinutes": 720}]'),
       ('d0000000-0000-4000-8000-000000000001', 'd0000000-0000-4000-8000-0000000000e2', '2026-11-01',
        '[{"code": "a", "name": "A", "start": "06:00", "durationMinutes": 480},
          {"code": "b", "name": "B", "start": "14:00", "durationMinutes": 480},
          {"code": "c", "name": "C", "start": "22:00", "durationMinutes": 480}]')
on conflict (project_id, valid_from) do nothing;

insert into project_access (company_id, project_id, user_id, granted_by)
values ('d0000000-0000-4000-8000-000000000001', 'd0000000-0000-4000-8000-0000000000e1',
        'd0000000-0000-4000-8000-0000000000a2', 'd0000000-0000-4000-8000-0000000000a1')
on conflict do nothing;
