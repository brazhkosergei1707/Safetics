-- Крок 5а: довідники рапорту (коди операцій, позиції витрат), одиниця обліку послуги, зміна і
-- бригада зміни, задача «Бурова на проєкті» (створюється при призначенні основного обладнання).

-- ---------------------------------------------------------------- системні дії й планувальник

-- Система (app.system) бачить усі проєкти своєї компанії: планувальник змін працює без користувача
create or replace function app_all_projects() returns boolean
    language sql stable as
$$
select (select app_system())
    or exists (select 1
               from membership m
                        join role_permission rp on rp.role_id = m.role_id
               where m.company_id = app_company_id()
                 and m.user_id = app_user_id()
                 and m.status = 'active'
                 and rp.permission_code = 'project.access_all')
$$;

-- Планувальник (app.scheduler у межах транзакції) лише перелічує активні компанії; далі працює в
-- контексті кожної компанії окремо
create function app_scheduler() returns boolean
    language sql stable as
$$ select coalesce(current_setting('app.scheduler', true), '') = 'on' $$;

create policy company_scheduler_select on company
    for select
    using ((select app_scheduler()));

-- ---------------------------------------------------------------- довідники

-- Коди операцій журналу часу. Категорія: productive — продуктивний, contractor_npt — непродуктивний
-- з вини підрядника, client_standby — простій з вини замовника, other — інше. billable — оплачуваний
-- (за замовчуванням з категорії, компанія змінює).
create table activity_code (
    id           uuid primary key default gen_random_uuid(),
    company_id   uuid        not null references company (id),
    code         text        not null check (code ~ '^[A-Za-z0-9][A-Za-z0-9_.-]{0,29}$'),
    name         jsonb       not null,
    category     text        not null check (category in ('productive', 'contractor_npt', 'client_standby', 'other')),
    billable     boolean     not null,
    sort_order   integer     not null default 0,
    template_key text,
    archived_at  timestamptz,
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now(),
    constraint activity_code_unique unique (company_id, code),
    constraint activity_code_id_company_unique unique (id, company_id)
);

-- Позиції витрат (номенклатура, пізніше спільна зі складом)
create table consumable_item (
    id             uuid primary key default gen_random_uuid(),
    company_id     uuid        not null references company (id),
    code           text        not null check (code ~ '^[a-z0-9][a-z0-9_.-]{0,49}$'),
    name           jsonb       not null,
    unit           text        not null check (length(trim(unit)) between 1 and 20),
    serial_tracked boolean     not null default false,
    template_key   text,
    archived_at    timestamptz,
    created_at     timestamptz not null default now(),
    updated_at     timestamptz not null default now(),
    constraint consumable_item_unique unique (company_id, code),
    constraint consumable_item_id_company_unique unique (id, company_id)
);

-- Одиниця обліку послуги: м (свердловина), м² (штукатурка), шт, замовлення… — для обсягів і цін
alter table service_type add column unit text check (unit in ('m', 'm2', 'm3', 'pcs', 'order', 'h'));

-- ---------------------------------------------------------------- «Бурова на проєкті»

-- primary_assignment: задача цього типу створюється на кожне призначення основного обладнання
alter table task_type add column auto_create text not null default 'none'
    check (auto_create in ('none', 'primary_assignment'));
alter table equipment_assignment add column task_id uuid references task (id);

-- ---------------------------------------------------------------- зміна

-- Зміна: основне обладнання + дата + зміна графіка — рівно одна. Час і тривалість копіюються з
-- графіка в момент створення (пізніша зміна графіка старих змін не чіпає).
create table work_shift (
    id               uuid primary key default gen_random_uuid(),
    company_id       uuid        not null references company (id),
    project_id       uuid        not null,
    asset_id         uuid        not null,
    shift_date       date        not null,
    shift_code       text        not null,
    shift_name       text        not null,
    starts_at        timestamptz not null,
    ends_at          timestamptz not null,
    duration_minutes integer     not null check (duration_minutes between 30 and 1440),
    source           text        not null default 'schedule' check (source in ('schedule', 'manual')),
    version          integer     not null default 1,
    created_by       uuid references app_user (id),
    created_at       timestamptz not null default now(),
    updated_at       timestamptz not null default now(),
    foreign key (project_id, company_id) references project (id, company_id),
    foreign key (asset_id, company_id) references asset (id, company_id),
    constraint work_shift_unique unique (asset_id, shift_date, shift_code),
    constraint work_shift_id_company_unique unique (id, company_id),
    constraint work_shift_times check (ends_at > starts_at)
);
create index work_shift_project_idx on work_shift (project_id, shift_date);

-- Бригада зміни: виконавець, посада на зміні, бурильник зміни (один), хвилини
create table work_shift_crew (
    id           uuid primary key default gen_random_uuid(),
    company_id   uuid    not null references company (id),
    shift_id     uuid    not null,
    personnel_id uuid    not null,
    position_id  uuid    not null,
    is_lead      boolean not null default false,
    minutes      integer not null check (minutes between 0 and 1440),
    sort_order   integer not null default 0,
    foreign key (shift_id, company_id) references work_shift (id, company_id) on delete cascade,
    foreign key (personnel_id, company_id) references personnel (id, company_id),
    foreign key (position_id, company_id) references position (id, company_id),
    constraint work_shift_crew_unique unique (shift_id, personnel_id)
);
create unique index work_shift_crew_one_lead on work_shift_crew (shift_id) where is_lead;

-- Задачі зміни (змінний рапорт, чек-лист)
alter table task add column work_shift_id uuid;
alter table task add constraint task_work_shift_fk foreign key (work_shift_id, company_id) references work_shift (id, company_id);
create index task_work_shift_idx on task (work_shift_id) where work_shift_id is not null;

-- ---------------------------------------------------------------- RLS

alter table activity_code enable row level security;
alter table activity_code force row level security;
create policy tenant_isolation on activity_code
    using (company_id = app_company_id())
    with check (company_id = app_company_id());

alter table consumable_item enable row level security;
alter table consumable_item force row level security;
create policy tenant_isolation on consumable_item
    using (company_id = app_company_id())
    with check (company_id = app_company_id());

-- Зміни й бригади — внутрішня робота підрядника, як задачі: портал їх не бачить
alter table work_shift enable row level security;
alter table work_shift force row level security;
create policy tenant_isolation on work_shift
    using (company_id = app_company_id() and app_can_see_project(project_id)
           and ((select app_has_permission('task.view')) or (select app_system())))
    with check (company_id = app_company_id() and app_can_see_project(project_id)
                and ((select app_has_permission('task.view')) or (select app_system())));

alter table work_shift_crew enable row level security;
alter table work_shift_crew force row level security;
create policy tenant_isolation on work_shift_crew
    using (company_id = app_company_id() and exists (select 1 from work_shift s where s.id = shift_id))
    with check (company_id = app_company_id() and exists (select 1 from work_shift s where s.id = shift_id));
