-- Крок 6а: перевірка змінного рапорту замовником. Статуси: draft → submitted → accepted (перевірено) /
-- returned (повернуто з коментарем) / corrected (виправлено замовником, чекає згоди підрядника).
-- Журнал подій рапорту з коментарями; подання — зі знімком; правка замовника — запропоновані значення.

alter table shift_report drop constraint shift_report_status_check;
alter table shift_report
    add constraint shift_report_status_check
        check (status in ('draft', 'submitted', 'returned', 'corrected', 'accepted')),
    add column accepted_at   timestamptz,
    add column accepted_by   uuid references app_user (id),
    add column correction_id uuid;   -- поточна правка замовника (подія corrected)

create table shift_report_event (
    id         uuid primary key default gen_random_uuid(),
    company_id uuid        not null references company (id),
    report_id  uuid        not null,
    kind       text        not null check (kind in ('submitted', 'returned', 'corrected', 'agreed', 'accepted', 'reopened')),
    comment    text,
    payload    jsonb,      -- submitted: знімок рапорту; corrected: запропоновані замовником журнал, інтервали, обсяги
    author_id  uuid references app_user (id),
    created_at timestamptz not null default now(),
    foreign key (report_id, company_id) references shift_report (id, company_id) on delete cascade
);
create index shift_report_event_report_idx on shift_report_event (report_id, created_at);

alter table shift_report_event enable row level security;
alter table shift_report_event force row level security;
create policy tenant_isolation on shift_report_event
    using (company_id = app_company_id() and exists (select 1 from shift_report r where r.id = report_id))
    with check (company_id = app_company_id() and exists (select 1 from shift_report r where r.id = report_id));

-- Статус задачі «Повернуто» (замовник повернув або виправив рапорт) — перед «Перевірено»
update task_type t
set statuses = jsonb_insert(t.statuses, array[(
        select (e.ord - 1)::text from jsonb_array_elements(t.statuses) with ordinality e(v, ord)
        where e.v ->> 'code' = 'reviewed')],
    '{"code": "returned", "label": {"en": "Returned", "uk": "Повернуто", "pt": "Devolvido"}, "category": "active", "color": "red"}'::jsonb)
where t.code = 'shift_report'
  and exists (select 1 from jsonb_array_elements(t.statuses) e where e ->> 'code' = 'reviewed')
  and not exists (select 1 from jsonb_array_elements(t.statuses) e where e ->> 'code' = 'returned');
