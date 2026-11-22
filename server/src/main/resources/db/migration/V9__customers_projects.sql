-- Модель v2, крок 2а: замовники, контактні особи, проєкти, графік змін, доступ до проєктів.
--
-- Доступ до проєкту (де людина працює в системі) окремо від призначення (де людина фізично):
--   * дозвіл project.access_all (адмін, керівник) — усі проєкти компанії;
--   * рядок у project_access — конкретний проєкт (видає менеджер; призначення людини з входом
--     додає його автоматично — крок 2б);
--   * контактна особа замовника з входом — усі проєкти свого замовника.
-- Обмеження діє в RLS: застосунок не може побачити чужий проєкт навіть через помилку в коді.

-- ---------------------------------------------------------------- замовник

create table customer (
    id          uuid primary key default gen_random_uuid(),
    company_id  uuid        not null references company (id),
    name        text        not null check (length(trim(name)) between 1 and 200),
    legal_code  text,                                    -- реєстраційний номер (CNPJ, ЄДРПОУ…)
    country     text check (country ~ '^[A-Z]{2}$'),     -- ISO 3166-1 alpha-2
    phone       text,
    email       text,
    notes       text,
    version     integer     not null default 1,
    archived_at timestamptz,
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now(),
    constraint customer_id_company_unique unique (id, company_id)
);
create unique index customer_name_unique on customer (company_id, lower(trim(name)));

-- Контактна особа. user_id заповнений, лише поки в неї є вхід у портал (членство з роллю замовника).
create table customer_contact (
    id          uuid primary key default gen_random_uuid(),
    company_id  uuid        not null references company (id),
    customer_id uuid        not null,
    full_name   text        not null check (length(trim(full_name)) between 1 and 200),
    job_title   text,                                    -- геолог, інженер, менеджер контракту…
    phone       text,
    email       text,
    user_id     uuid,
    version     integer     not null default 1,
    archived_at timestamptz,
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now(),
    foreign key (customer_id, company_id) references customer (id, company_id),
    -- вхід лише для члена цієї ж компанії
    foreign key (company_id, user_id) references membership (company_id, user_id),
    constraint customer_contact_user_unique unique (company_id, user_id)
);
create index customer_contact_customer_idx on customer_contact (customer_id);

-- ---------------------------------------------------------------- проєкт

create table project (
    id          uuid primary key default gen_random_uuid(),
    company_id  uuid        not null references company (id),
    customer_id uuid,                                    -- порожній у постійного проєкту (шиномонтаж)
    code        text        not null check (length(trim(code)) between 1 and 50),
    name        text        not null check (length(trim(name)) between 1 and 200),
    location    text,
    country     text check (country ~ '^[A-Z]{2}$'),
    time_zone   text        not null,                    -- IANA, напр. America/Belem; перевіряє код
    start_date  date        not null,
    end_date    date,
    status      text        not null default 'active' check (status in ('active', 'closed')),
    version     integer     not null default 1,
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now(),
    foreign key (customer_id, company_id) references customer (id, company_id),
    constraint project_id_company_unique unique (id, company_id),
    constraint project_dates check (end_date is null or end_date >= start_date)
);
create unique index project_code_unique on project (company_id, lower(trim(code)));
create index project_customer_idx on project (customer_id) where customer_id is not null;

-- Графік змін: версія діє з valid_from до наступної версії. Для дат до першої версії діє перша.
-- shifts: [{"code": "day", "name": "Денна", "start": "07:00", "durationMinutes": 720}, ...]
create table shift_schedule (
    id         uuid primary key default gen_random_uuid(),
    company_id uuid        not null references company (id),
    project_id uuid        not null,
    valid_from date        not null,
    shifts     jsonb       not null check (jsonb_typeof(shifts) = 'array' and jsonb_array_length(shifts) > 0),
    created_by uuid references app_user (id),
    created_at timestamptz not null default now(),
    foreign key (project_id, company_id) references project (id, company_id) on delete cascade,
    constraint shift_schedule_date_unique unique (project_id, valid_from)
);

-- Доступ до проєкту — лише для члена компанії (складений ключ на membership).
create table project_access (
    company_id uuid        not null references company (id),
    project_id uuid        not null,
    user_id    uuid        not null,
    granted_by uuid references app_user (id),
    created_at timestamptz not null default now(),
    primary key (project_id, user_id),
    foreign key (project_id, company_id) references project (id, company_id) on delete cascade,
    foreign key (company_id, user_id) references membership (company_id, user_id)
);
create index project_access_user_idx on project_access (user_id);

-- ---------------------------------------------------------------- видимість проєктів

-- Чи має поточний користувач дозвіл project.access_all у поточній компанії.
-- У політиках викликається як (select app_all_projects()) — рахується один раз на запит.
create function app_all_projects() returns boolean
    language sql stable as
$$
select exists (select 1
               from membership m
                        join role_permission rp on rp.role_id = m.role_id
               where m.company_id = app_company_id()
                 and m.user_id = app_user_id()
                 and m.status = 'active'
                 and rp.permission_code = 'project.access_all')
$$;

-- Правило видимості для рядка project. project_access і customer_contact мають лише
-- політику компанії, тому рекурсії політик немає.
create function app_sees_project(p_project_id uuid, p_customer_id uuid) returns boolean
    language sql stable as
$$
select (select app_all_projects())
    or exists (select 1
               from project_access pa
               where pa.project_id = p_project_id
                 and pa.user_id = app_user_id())
    or (p_customer_id is not null and exists (select 1
                                              from customer_contact cc
                                              where cc.customer_id = p_customer_id
                                                and cc.user_id = app_user_id()
                                                and cc.archived_at is null))
$$;

-- Для таблиць, що належать проєкту (графік змін, згодом призначення, послуги, рапорти, задачі):
-- рядок видно, якщо видно сам проєкт (RLS project застосовується всередині).
create function app_can_see_project(p_project_id uuid) returns boolean
    language sql stable as
$$
select exists (select 1 from project p where p.id = p_project_id)
$$;

-- ---------------------------------------------------------------- RLS

alter table customer enable row level security;
alter table customer force row level security;
create policy tenant_isolation on customer
    using (company_id = app_company_id())
    with check (company_id = app_company_id());

alter table customer_contact enable row level security;
alter table customer_contact force row level security;
create policy tenant_isolation on customer_contact
    using (company_id = app_company_id())
    with check (company_id = app_company_id());

-- Створити проєкт може будь-хто в межах компанії (дозвіл перевіряє код); сервіс одразу дає
-- автору доступ. Тому insert не можна робити з returning: новий рядок ще не видно автору.
alter table project enable row level security;
alter table project force row level security;
create policy project_select on project
    for select
    using (company_id = app_company_id() and app_sees_project(id, customer_id));
create policy project_insert on project
    for insert
    with check (company_id = app_company_id());
create policy project_update on project
    for update
    using (company_id = app_company_id() and app_sees_project(id, customer_id))
    with check (company_id = app_company_id());
create policy project_delete on project
    for delete
    using (company_id = app_company_id() and app_sees_project(id, customer_id));

alter table shift_schedule enable row level security;
alter table shift_schedule force row level security;
create policy tenant_isolation on shift_schedule
    using (company_id = app_company_id() and app_can_see_project(project_id))
    with check (company_id = app_company_id() and app_can_see_project(project_id));

alter table project_access enable row level security;
alter table project_access force row level security;
create policy tenant_isolation on project_access
    using (company_id = app_company_id())
    with check (company_id = app_company_id());

-- ---------------------------------------------------------------- дозволи

-- Замовники — наявні client.view / client.edit. Проєкти, графік, доступ, вхід контактних осіб
-- у портал — project.manage, тепер і в керівника та менеджера.
insert into role_permission (role_id, permission_code)
select r.id, 'project.manage'
from role r
where r.company_id is null
  and r.code in ('director', 'manager')
on conflict do nothing;

update permission
set description = 'Проєкти: створення, графік змін, доступ, призначення людей і обладнання, вхід замовника в портал'
where code = 'project.manage';
