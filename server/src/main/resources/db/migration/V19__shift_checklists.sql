-- Крок 5г: чек-лист зміни. Шаблон — у типі обладнання (asset_type.checklist: пункти з позначкою
-- «критичний»), заповнений чек-лист — документ задачі «Чек-лист зміни». Не пройдено → задача «Сервіс»
-- на супервайзера; критичний пункт → ще й алерт менеджерам проєкту.

alter table asset_type add column checklist jsonb not null default '[]';

-- Заповнений чек-лист: пункти з відповідями (pass / fail / na) — знімок на момент подання
create table shift_checklist (
    id              uuid primary key default gen_random_uuid(),
    company_id      uuid        not null references company (id),
    shift_id        uuid        not null,
    asset_id        uuid        not null,
    result          text        not null check (result in ('passed', 'failed')),
    items           jsonb       not null,
    comment         text,
    service_task_id uuid references task (id),
    submitted_by    uuid references app_user (id),
    submitted_at    timestamptz not null default now(),
    foreign key (shift_id, company_id) references work_shift (id, company_id) on delete cascade,
    foreign key (asset_id, company_id) references asset (id, company_id),
    constraint shift_checklist_unique unique (shift_id, asset_id)
);

-- Алерти користувачам (поки — у вебі; push — разом із сервісом сповіщень)
create table alert (
    id         uuid primary key default gen_random_uuid(),
    company_id uuid        not null references company (id),
    user_id    uuid        not null references app_user (id),
    project_id uuid,
    kind       text        not null,
    title      text        not null,
    task_id    uuid references task (id),
    created_at timestamptz not null default now(),
    read_at    timestamptz,
    foreign key (project_id, company_id) references project (id, company_id)
);
create index alert_user_idx on alert (user_id, created_at desc);

alter table shift_checklist enable row level security;
alter table shift_checklist force row level security;
create policy tenant_isolation on shift_checklist
    using (company_id = app_company_id() and exists (select 1 from work_shift s where s.id = shift_id))
    with check (company_id = app_company_id() and exists (select 1 from work_shift s where s.id = shift_id));

-- Свої алерти; створює система (від імені того, хто подав чек-лист)
alter table alert enable row level security;
alter table alert force row level security;
create policy tenant_isolation on alert
    using (company_id = app_company_id() and (user_id = app_user_id() or (select app_system())))
    with check (company_id = app_company_id() and (user_id = app_user_id() or (select app_system())));

-- Бурові установки з шаблонів: стандартний чек-лист
update asset_type
set checklist  = '[{"key": "fire_extinguishers", "label": {"en": "Fire extinguishers in place and charged", "uk": "Вогнегасники на місці й заряджені", "pt": "Extintores no lugar e carregados"}, "critical": true}, {"key": "emergency_stops", "label": {"en": "Emergency stops work", "uk": "Аварійні зупинки працюють", "pt": "Paradas de emergência funcionam"}, "critical": true}, {"key": "guards", "label": {"en": "Rotating parts guards in place", "uk": "Огородження обертових частин на місці", "pt": "Proteções das partes rotativas no lugar"}, "critical": true}, {"key": "hp_hoses", "label": {"en": "High-pressure hoses and whip checks intact", "uk": "Шланги високого тиску й страхувальні тросики цілі", "pt": "Mangueiras de alta pressão e travas intactas"}, "critical": true}, {"key": "wire_rope", "label": {"en": "Wire rope and winch without damage", "uk": "Трос і лебідка без пошкоджень", "pt": "Cabo de aço e guincho sem danos"}, "critical": true}, {"key": "hydraulic_leaks", "label": {"en": "No hydraulic or fuel leaks", "uk": "Немає витоків гідравліки й палива", "pt": "Sem vazamentos hidráulicos ou de combustível"}, "critical": false}, {"key": "fluid_levels", "label": {"en": "Oil, coolant and hydraulic levels OK", "uk": "Рівні мастила, охолоджувальної і гідравлічної рідини в нормі", "pt": "Níveis de óleo, arrefecimento e hidráulico OK"}, "critical": false}, {"key": "ppe", "label": {"en": "Crew PPE available", "uk": "ЗІЗ бригади в наявності", "pt": "EPI da equipe disponível"}, "critical": false}, {"key": "first_aid", "label": {"en": "First aid kit complete", "uk": "Аптечка укомплектована", "pt": "Kit de primeiros socorros completo"}, "critical": false}, {"key": "housekeeping", "label": {"en": "Site tidy, no trip hazards", "uk": "Порядок на майданчику, немає перешкод", "pt": "Área organizada, sem riscos de tropeço"}, "critical": false}]',
    updated_at = now()
where code = 'drill_rig'
  and template_key in ('exploration-drilling', 'water-well-drilling')
  and checklist = '[]';
