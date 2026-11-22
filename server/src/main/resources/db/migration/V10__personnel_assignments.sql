-- Модель v2, крок 2б: посади, виконавці, призначення людей і обладнання на проєкт.
--
-- Людина ≠ обліковий запис: виконавець — запис компанії, вхід у систему (user_id) необов'язковий.
-- Призначення — фізична присутність на проєкті з датами «з / по» (вахти, ротації). Людина чи
-- обладнання не можуть бути на двох проєктах одночасно: обмеження exclude в базі. Доступ до
-- проєкту (project_access) — окремо; призначення людини з входом додає його автоматично (сервіс).

-- btree_gist потрібен, щоб в exclude поєднати рівність (uuid) і перекриття діапазонів дат
create extension if not exists btree_gist;

-- ---------------------------------------------------------------- посада

-- project_role — роль на проєкті, яку дає посада: supervisor (супервайзер проєкту), storekeeper
-- (комірник), none. За нею правила задач знаходять «супервайзера проєкту».
-- supervisor_can_add — супервайзер може сам додати на свій проєкт людину без входу з цією посадою.
create table position (
    id                 uuid primary key default gen_random_uuid(),
    company_id         uuid        not null references company (id),
    code               text        not null check (code ~ '^[a-z][a-z0-9_]{1,49}$'),
    name               jsonb       not null,                -- {"en": "Driller", "uk": "Бурильник"}
    project_role       text        not null default 'none' check (project_role in ('supervisor', 'storekeeper', 'none')),
    supervisor_can_add boolean     not null default false,
    template_key       text,
    archived_at        timestamptz,
    created_at         timestamptz not null default now(),
    updated_at         timestamptz not null default now(),
    constraint position_code_unique unique (company_id, code),
    constraint position_id_company_unique unique (id, company_id)
);

-- ---------------------------------------------------------------- виконавець

create table personnel (
    id              uuid primary key default gen_random_uuid(),
    company_id      uuid        not null references company (id),
    full_name       text        not null check (length(trim(full_name)) between 1 and 200),
    phone           text,
    employment_type text        not null default 'staff' check (employment_type in ('staff', 'subcontractor', 'hired')),
    organization    text,                                   -- субпідрядник / агентство для нештатних
    position_id     uuid,                                   -- посада за замовчуванням
    user_id         uuid,                                   -- вхід у систему, якщо є
    notes           text,
    version         integer     not null default 1,
    archived_at     timestamptz,
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now(),
    foreign key (position_id, company_id) references position (id, company_id),
    foreign key (company_id, user_id) references membership (company_id, user_id),
    constraint personnel_user_unique unique (company_id, user_id),
    constraint personnel_id_company_unique unique (id, company_id)
);
create index personnel_company_idx on personnel (company_id) where archived_at is null;

-- ---------------------------------------------------------------- призначення

-- end_date null — без дати завершення. Діапазон включний з обох боків: '[]'.
create table personnel_assignment (
    id           uuid primary key default gen_random_uuid(),
    company_id   uuid        not null references company (id),
    project_id   uuid        not null,
    personnel_id uuid        not null,
    position_id  uuid        not null,                      -- посада на цьому проєкті
    start_date   date        not null,
    end_date     date,
    created_by   uuid references app_user (id),
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now(),
    foreign key (project_id, company_id) references project (id, company_id),
    foreign key (personnel_id, company_id) references personnel (id, company_id),
    foreign key (position_id, company_id) references position (id, company_id),
    constraint personnel_assignment_dates check (end_date is null or end_date >= start_date),
    constraint personnel_assignment_no_overlap exclude using gist (
        personnel_id with =,
        daterange(start_date, end_date, '[]') with &&)
);
create index personnel_assignment_project_idx on personnel_assignment (project_id);

create table equipment_assignment (
    id         uuid primary key default gen_random_uuid(),
    company_id uuid        not null references company (id),
    project_id uuid        not null,
    asset_id   uuid        not null,
    start_date date        not null,
    end_date   date,
    created_by uuid references app_user (id),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    foreign key (project_id, company_id) references project (id, company_id),
    foreign key (asset_id, company_id) references asset (id, company_id),
    constraint equipment_assignment_dates check (end_date is null or end_date >= start_date),
    constraint equipment_assignment_no_overlap exclude using gist (
        asset_id with =,
        daterange(start_date, end_date, '[]') with &&)
);
create index equipment_assignment_project_idx on equipment_assignment (project_id);

-- ---------------------------------------------------------------- RLS

alter table position enable row level security;
alter table position force row level security;
create policy tenant_isolation on position
    using (company_id = app_company_id())
    with check (company_id = app_company_id());

alter table personnel enable row level security;
alter table personnel force row level security;
create policy tenant_isolation on personnel
    using (company_id = app_company_id())
    with check (company_id = app_company_id());

-- Призначення видно разом із проєктом (бригада проєкту, обладнання на проєкті)
alter table personnel_assignment enable row level security;
alter table personnel_assignment force row level security;
create policy tenant_isolation on personnel_assignment
    using (company_id = app_company_id() and app_can_see_project(project_id))
    with check (company_id = app_company_id() and app_can_see_project(project_id));

alter table equipment_assignment enable row level security;
alter table equipment_assignment force row level security;
create policy tenant_isolation on equipment_assignment
    using (company_id = app_company_id() and app_can_see_project(project_id))
    with check (company_id = app_company_id() and app_can_see_project(project_id));

-- ---------------------------------------------------------------- дозволи

insert into permission (code, description) values
    ('personnel.manage', 'Виконавці: створення, редагування, архів, вхід у систему (не більше прав, ніж маєш сам)');

insert into role_permission (role_id, permission_code)
select r.id, 'personnel.manage'
from role r
where r.company_id is null
  and r.code in ('admin', 'director', 'manager');

update permission
set description = 'Каталог послуг, ціни, типи обладнання, посади, форми, підстатуси'
where code = 'catalog.manage';
