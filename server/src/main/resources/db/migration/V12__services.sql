-- Крок 3: тип послуги й послуга (свердловина, замовлення меблів, шиномонтаж…).
--
-- Послуга належить проєкту; обладнання виконує її через документи (рапорти), прямого зв'язку немає.
-- Поля послуги задає тип (fields, як у типу обладнання), значення — у attributes, перевіряє код.
-- Статуси однакові для всіх галузей: planned → in_progress → completed; cancelled.

create table service_type (
    id           uuid primary key default gen_random_uuid(),
    company_id   uuid        not null references company (id),
    code         text        not null check (code ~ '^[a-z][a-z0-9_]{1,49}$'),
    name         jsonb       not null,                -- {"en": "Drill hole", "uk": "Свердловина"}
    fields       jsonb       not null default '[]',   -- [FieldDefinition]
    template_key text,
    archived_at  timestamptz,
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now(),
    constraint service_type_code_unique unique (company_id, code),
    constraint service_type_id_company_unique unique (id, company_id)
);

create table service (
    id              uuid primary key default gen_random_uuid(),
    company_id      uuid        not null references company (id),
    project_id      uuid        not null,
    service_type_id uuid        not null,
    code            text        not null check (length(trim(code)) between 1 and 100),
    name            text,
    -- Лише якщо в проєкті немає замовника (постійний проєкт: меблі, шиномонтаж); інакше — замовник проєкту
    customer_id     uuid,
    status          text        not null default 'planned'
        check (status in ('planned', 'in_progress', 'completed', 'cancelled')),
    attributes      jsonb       not null default '{}',
    planned_start   date,
    planned_end     date,
    actual_start    timestamptz,
    actual_end      timestamptz,
    cancel_reason   text,
    created_by      uuid references app_user (id),
    version         integer     not null default 1,
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now(),
    foreign key (project_id, company_id) references project (id, company_id),
    foreign key (service_type_id, company_id) references service_type (id, company_id),
    foreign key (customer_id, company_id) references customer (id, company_id),
    constraint service_id_company_unique unique (id, company_id),
    constraint service_planned_dates check (planned_end is null or planned_start is null or planned_end >= planned_start),
    constraint service_cancel_reason check (status <> 'cancelled' or cancel_reason is not null)
);
create unique index service_code_unique on service (project_id, lower(trim(code)));
create index service_project_status_idx on service (project_id, status);

alter table service_type enable row level security;
alter table service_type force row level security;
create policy tenant_isolation on service_type
    using (company_id = app_company_id())
    with check (company_id = app_company_id());

-- Послугу видно разом із проєктом (зокрема представнику замовника на проєктах свого замовника)
alter table service enable row level security;
alter table service force row level security;
create policy tenant_isolation on service
    using (company_id = app_company_id() and app_can_see_project(project_id))
    with check (company_id = app_company_id() and app_can_see_project(project_id));

-- service.plan — додавати й змінювати заплановані послуги (менеджери й ролі порталу замовника).
-- Супервайзер проєкту працює з послугами свого проєкту за посадою (перевіряє код), без дозволу.
insert into permission (code, description) values
    ('service.plan', 'Послуги: планувати (додавати, змінювати, скасовувати заплановані) на видимих проєктах');

insert into role_permission (role_id, permission_code)
select r.id, 'service.plan'
from role r
where r.company_id is null
  and r.code in ('admin', 'director', 'manager', 'client_rep', 'client_manager');
