-- Крок 4а: задачі (як Work Items / Jira): типи зі статусами за категоріями, зв'язки, історія,
-- форма закриття. Задача — шар «хто, що і до коли» над послугами й обладнанням.

-- ---------------------------------------------------------------- дозволи

-- Чи має поточний користувач дозвіл у поточній компанії (з ролі членства).
-- У політиках — як (select app_has_permission('…')), рахується один раз на запит.
create function app_has_permission(p_code text) returns boolean
    language sql stable as
$$
select exists (select 1
               from membership m
                        join role_permission rp on rp.role_id = m.role_id
               where m.company_id = app_company_id()
                 and m.user_id = app_user_id()
                 and m.status = 'active'
                 and rp.permission_code = p_code)
$$;

-- Задачі — внутрішня робота підрядника: портал замовника їх не бачить (немає task.view)
insert into permission (code, description) values
    ('task.view', 'Задачі: перегляд на видимих проєктах (зміни — менеджер, супервайзер проєкту, виконавець своїх)');

insert into role_permission (role_id, permission_code)
select r.id, 'task.view'
from role r
where r.company_id is null
  and r.code in ('admin', 'director', 'manager', 'worker');

-- ---------------------------------------------------------------- тип задачі

-- statuses: [{"code": "new", "label": {...}, "category": "new|active|done|cancelled", "color": "blue"}],
--           перший — статус нової задачі (категорія new).
-- fields — власні поля задачі; closing_fields — форма закриття (обов'язкові для переходу в done).
-- subject_kind — предмет задачі: none / service / asset.
-- sync_service — задача в роботі переводить послугу в «в роботі», завершена — в «завершено».
-- children_block_close — не закривати, поки є незавершені підзадачі.
create table task_type (
    id                   uuid primary key default gen_random_uuid(),
    company_id           uuid        not null references company (id),
    code                 text        not null check (code ~ '^[a-z][a-z0-9_]{1,49}$'),
    name                 jsonb       not null,
    statuses             jsonb       not null,
    fields               jsonb       not null default '[]',
    closing_fields       jsonb       not null default '[]',
    subject_kind         text        not null default 'none' check (subject_kind in ('none', 'service', 'asset')),
    sync_service         boolean     not null default false,
    children_block_close boolean     not null default false,
    template_key         text,
    archived_at          timestamptz,
    created_at           timestamptz not null default now(),
    updated_at           timestamptz not null default now(),
    constraint task_type_code_unique unique (company_id, code),
    constraint task_type_id_company_unique unique (id, company_id)
);

-- ---------------------------------------------------------------- задача

-- Номер задачі в межах компанії (#123)
create table task_counter (
    company_id  uuid primary key references company (id),
    last_number bigint not null
);

create table task (
    id              uuid primary key default gen_random_uuid(),
    company_id      uuid        not null references company (id),
    project_id      uuid        not null,
    task_type_id    uuid        not null,
    number          bigint      not null,
    title           text        not null check (length(trim(title)) between 1 and 300),
    description     text,
    status          text        not null,
    status_category text        not null check (status_category in ('new', 'active', 'done', 'cancelled')),
    assignee_id     uuid,                                   -- лише людина з входом
    author_id       uuid references app_user (id),
    service_id      uuid,
    asset_id        uuid,
    parent_id       uuid,
    planned_start   date,
    planned_end     date,
    forecast_end    date,
    actual_start    timestamptz,
    actual_end      timestamptz,
    attributes      jsonb       not null default '{}',
    closing_values  jsonb,
    version         integer     not null default 1,
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now(),
    foreign key (project_id, company_id) references project (id, company_id),
    foreign key (task_type_id, company_id) references task_type (id, company_id),
    foreign key (company_id, assignee_id) references membership (company_id, user_id),
    foreign key (service_id, company_id) references service (id, company_id),
    foreign key (asset_id, company_id) references asset (id, company_id),
    foreign key (parent_id) references task (id),
    constraint task_number_unique unique (company_id, number),
    constraint task_id_company_unique unique (id, company_id),
    constraint task_not_own_parent check (parent_id is null or parent_id <> id),
    constraint task_planned_dates check (planned_end is null or planned_start is null or planned_end >= planned_start)
);
create index task_project_category_idx on task (project_id, status_category);
create index task_assignee_idx on task (assignee_id) where status_category in ('new', 'active');
create index task_parent_idx on task (parent_id) where parent_id is not null;
create index task_service_idx on task (service_id) where service_id is not null;

-- blocks: from блокує to (to не почати й не закрити, поки from не завершена); relates — без правил
create table task_link (
    id         uuid primary key default gen_random_uuid(),
    company_id uuid        not null references company (id),
    from_task  uuid        not null,
    to_task    uuid        not null,
    kind       text        not null check (kind in ('blocks', 'relates')),
    created_by uuid references app_user (id),
    created_at timestamptz not null default now(),
    foreign key (from_task, company_id) references task (id, company_id) on delete cascade,
    foreign key (to_task, company_id) references task (id, company_id) on delete cascade,
    constraint task_link_unique unique (from_task, to_task, kind),
    constraint task_link_not_self check (from_task <> to_task)
);
create index task_link_to_idx on task_link (to_task);

-- Історія лише на додавання
create table task_history (
    id         bigint generated always as identity primary key,
    company_id uuid        not null references company (id),
    task_id    uuid        not null,
    user_id    uuid references app_user (id),
    action     text        not null,
    changes    jsonb,
    created_at timestamptz not null default now(),
    foreign key (task_id, company_id) references task (id, company_id) on delete cascade
);
create index task_history_task_idx on task_history (task_id, created_at);
revoke update, delete, truncate on task_history from ${appdbuser};

-- ---------------------------------------------------------------- RLS

alter table task_type enable row level security;
alter table task_type force row level security;
create policy tenant_isolation on task_type
    using (company_id = app_company_id())
    with check (company_id = app_company_id());

alter table task_counter enable row level security;
alter table task_counter force row level security;
create policy tenant_isolation on task_counter
    using (company_id = app_company_id())
    with check (company_id = app_company_id());

-- Задачу видно, якщо видно проєкт і є task.view (портал замовника задач не бачить)
alter table task enable row level security;
alter table task force row level security;
create policy tenant_isolation on task
    using (company_id = app_company_id() and (select app_has_permission('task.view')) and app_can_see_project(project_id))
    with check (company_id = app_company_id() and (select app_has_permission('task.view')) and app_can_see_project(project_id));

alter table task_link enable row level security;
alter table task_link force row level security;
create policy tenant_isolation on task_link
    using (company_id = app_company_id() and (select app_has_permission('task.view')))
    with check (company_id = app_company_id() and (select app_has_permission('task.view')));

alter table task_history enable row level security;
alter table task_history force row level security;
create policy tenant_isolation on task_history
    using (company_id = app_company_id() and (select app_has_permission('task.view')))
    with check (company_id = app_company_id() and (select app_has_permission('task.view')));
