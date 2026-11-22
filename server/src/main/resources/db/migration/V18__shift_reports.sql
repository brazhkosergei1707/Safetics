-- Крок 5б: змінний рапорт (1:1 зі зміною) — журнал часу, інтервали буріння, обсяги, витрати,
-- лічильники. Чек-лист зміни (задача) разом із рапортом у компаніях, де шаблон уже встановлено.

create table shift_report (
    id           uuid primary key default gen_random_uuid(),
    company_id   uuid        not null references company (id),
    shift_id     uuid        not null,
    status       text        not null default 'draft' check (status in ('draft', 'submitted')),
    notes        text,
    submitted_at timestamptz,
    submitted_by uuid references app_user (id),
    version      integer     not null default 1,
    created_by   uuid references app_user (id),
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now(),
    foreign key (shift_id, company_id) references work_shift (id, company_id) on delete cascade,
    constraint shift_report_shift_unique unique (shift_id),
    constraint shift_report_id_company_unique unique (id, company_id)
);

-- Журнал часу: хвилини від початку зміни [start, end)
create table shift_report_time (
    id               uuid primary key default gen_random_uuid(),
    company_id       uuid    not null references company (id),
    report_id        uuid    not null,
    start_minute     integer not null,
    end_minute       integer not null,
    activity_code_id uuid    not null,
    task_id          uuid references task (id),
    comment          text,
    sort_order       integer not null default 0,
    foreign key (report_id, company_id) references shift_report (id, company_id) on delete cascade,
    foreign key (activity_code_id, company_id) references activity_code (id, company_id),
    constraint shift_report_time_range check (start_minute >= 0 and end_minute > start_minute and end_minute <= 1440)
);

-- Інтервали буріння: свердловина (послуга), глибина від / до, типорозмір, довжина керна
create table shift_report_interval (
    id          uuid primary key default gen_random_uuid(),
    company_id  uuid          not null references company (id),
    report_id   uuid          not null,
    service_id  uuid          not null references service (id),
    depth_from  numeric(10, 2) not null check (depth_from >= 0),
    depth_to    numeric(10, 2) not null,
    core_size   text,
    core_length numeric(10, 2) check (core_length >= 0),
    comment     text,
    sort_order  integer       not null default 0,
    foreign key (report_id, company_id) references shift_report (id, company_id) on delete cascade,
    constraint shift_report_interval_depth check (depth_to > depth_from)
);

-- Обсяги в одиниці типу послуги (м², замовлення…); метри буріння — з інтервалів
create table shift_report_quantity (
    id         uuid primary key default gen_random_uuid(),
    company_id uuid           not null references company (id),
    report_id  uuid           not null,
    service_id uuid           not null references service (id),
    quantity   numeric(14, 3) not null check (quantity > 0),
    comment    text,
    sort_order integer        not null default 0,
    foreign key (report_id, company_id) references shift_report (id, company_id) on delete cascade
);

create table shift_report_consumable (
    id         uuid primary key default gen_random_uuid(),
    company_id uuid           not null references company (id),
    report_id  uuid           not null,
    item_id    uuid           not null,
    quantity   numeric(14, 3) not null check (quantity > 0),
    comment    text,
    sort_order integer        not null default 0,
    foreign key (report_id, company_id) references shift_report (id, company_id) on delete cascade,
    foreign key (item_id, company_id) references consumable_item (id, company_id)
);

-- Лічильники (мотогодини) бурової і вкладеного обладнання на початок і кінець зміни
create table shift_report_meter (
    id          uuid primary key default gen_random_uuid(),
    company_id  uuid           not null references company (id),
    report_id   uuid           not null,
    asset_id    uuid           not null,
    start_value numeric(12, 1) not null check (start_value >= 0),
    end_value   numeric(12, 1) not null,
    foreign key (report_id, company_id) references shift_report (id, company_id) on delete cascade,
    foreign key (asset_id, company_id) references asset (id, company_id),
    constraint shift_report_meter_unique unique (report_id, asset_id),
    constraint shift_report_meter_range check (end_value >= start_value)
);

create index shift_report_interval_service_idx on shift_report_interval (service_id);

-- ---------------------------------------------------------------- RLS: як у зміни (портал не бачить)

alter table shift_report enable row level security;
alter table shift_report force row level security;
create policy tenant_isolation on shift_report
    using (company_id = app_company_id() and exists (select 1 from work_shift s where s.id = shift_id))
    with check (company_id = app_company_id() and exists (select 1 from work_shift s where s.id = shift_id));

do
$$
    declare
        t text;
    begin
        foreach t in array array ['shift_report_time', 'shift_report_interval', 'shift_report_quantity',
            'shift_report_consumable', 'shift_report_meter']
            loop
                execute format('alter table %I enable row level security', t);
                execute format('alter table %I force row level security', t);
                execute format($p$create policy tenant_isolation on %I
                    using (company_id = app_company_id() and exists (select 1 from shift_report r where r.id = report_id))
                    with check (company_id = app_company_id() and exists (select 1 from shift_report r where r.id = report_id))$p$, t);
            end loop;
    end
$$;

-- ---------------------------------------------------------------- чек-лист у наявних компаніях

-- Тип «Чек-лист зміни» там, де є «Бурова на проєкті» з шаблону
insert into task_type (company_id, code, name, statuses, subject_kind, template_key)
select tt.company_id, 'shift_checklist',
       '{"en": "Pre-start checklist", "uk": "Чек-лист зміни", "pt": "Checklist pré-operacional"}',
       '[{"code": "expected", "label": {"en": "Expected", "uk": "Очікується", "pt": "Aguardado"}, "category": "new", "color": "default"},
         {"code": "passed", "label": {"en": "Passed", "uk": "Пройдено", "pt": "Aprovado"}, "category": "done", "color": "green"},
         {"code": "failed", "label": {"en": "Failed", "uk": "Не пройдено", "pt": "Reprovado"}, "category": "done", "color": "red"},
         {"code": "cancelled", "label": {"en": "Cancelled", "uk": "Скасовано", "pt": "Cancelado"}, "category": "cancelled"}]',
       'asset', tt.template_key
from task_type tt
where tt.code = 'rig_on_project'
  and tt.template_key is not null
on conflict (company_id, code) do nothing;

-- Правило шаблону без змін (лише рапорт) → чек-лист + рапорт, який блокується чек-листом
update task_type
set rules      = '[{"trigger": "shift_created", "category": null, "action": "create_subtask", "taskType": "shift_checklist",
                    "title": "{type}: {asset} · {shift}", "assignTo": "shift_lead", "dueInDays": 1, "blockedBy": null},
                   {"trigger": "shift_created", "category": null, "action": "create_subtask", "taskType": "shift_report",
                    "title": "{type}: {asset} · {shift}", "assignTo": "shift_lead", "dueInDays": 1, "blockedBy": 0}]',
    updated_at = now()
where code = 'rig_on_project'
  and template_key is not null
  and jsonb_array_length(rules) = 1
  and rules -> 0 ->> 'taskType' = 'shift_report'
  and rules -> 0 ->> 'blockedBy' is null;
