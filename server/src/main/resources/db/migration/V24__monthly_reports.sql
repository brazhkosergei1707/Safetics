-- Крок 6б: місячний звіт (проєкт × календарний місяць) з версіями, ручними коригуваннями,
-- неперевіреними рапортами за вибором менеджера і закриттям місяця після затвердження замовником.

create table monthly_report (
    id              uuid primary key default gen_random_uuid(),
    company_id      uuid        not null references company (id),
    project_id      uuid        not null,
    month           date        not null check (extract(day from month) = 1),
    version         integer     not null check (version >= 1),
    status          text        not null default 'draft'
        check (status in ('draft', 'sent', 'approved', 'returned', 'superseded')),
    content         jsonb       not null,   -- знімок: бурові, неперевірені рапорти, коригування, підсумки
    generated_by    uuid references app_user (id),
    generated_at    timestamptz not null default now(),
    sent_by         uuid references app_user (id),
    sent_at         timestamptz,
    decided_by      uuid references app_user (id),
    decided_at      timestamptz,
    decision_comment text,
    unique (project_id, month, version),
    unique (id, company_id),
    foreign key (project_id, company_id) references project (id, company_id)
);
create index monthly_report_project_idx on monthly_report (project_id, month desc, version desc);

-- Неперевірені рапорти, які менеджер включив в основну частину звіту місяця
create table monthly_report_inclusion (
    company_id uuid        not null references company (id),
    project_id uuid        not null,
    month      date        not null,
    shift_id   uuid        not null,
    created_by uuid references app_user (id),
    created_at timestamptz not null default now(),
    primary key (project_id, month, shift_id),
    foreign key (project_id, company_id) references project (id, company_id),
    foreign key (shift_id, company_id) references work_shift (id, company_id) on delete cascade
);

-- Ручне коригування місяця (метри, оплачувані години, обсяг) з обов'язковим коментарем;
-- коригувальний запис за закритий місяць — з посиланням на зміну, рапорт якої виправляє
create table monthly_adjustment (
    id               uuid primary key default gen_random_uuid(),
    company_id       uuid          not null references company (id),
    project_id       uuid          not null,
    month            date          not null check (extract(day from month) = 1),
    kind             text          not null check (kind in ('meters', 'hours', 'quantity')),
    asset_id         uuid,
    amount           numeric(14, 2) not null check (amount <> 0),
    unit             text,
    comment          text          not null check (length(trim(comment)) between 1 and 1000),
    corrects_shift_id uuid,
    created_by       uuid references app_user (id),
    created_at       timestamptz   not null default now(),
    foreign key (project_id, company_id) references project (id, company_id),
    foreign key (asset_id, company_id) references asset (id, company_id),
    foreign key (corrects_shift_id, company_id) references work_shift (id, company_id)
);
create index monthly_adjustment_month_idx on monthly_adjustment (project_id, month);

-- Закритий місяць: рапорти його змін заблоковані
create table project_month_close (
    company_id uuid        not null references company (id),
    project_id uuid        not null,
    month      date        not null,
    report_id  uuid        not null,
    closed_by  uuid references app_user (id),
    closed_at  timestamptz not null default now(),
    primary key (project_id, month),
    foreign key (project_id, company_id) references project (id, company_id),
    foreign key (report_id, company_id) references monthly_report (id, company_id)
);

do
$$
    declare
        t text;
    begin
        foreach t in array array ['monthly_report', 'monthly_report_inclusion', 'monthly_adjustment', 'project_month_close']
            loop
                execute format('alter table %I enable row level security', t);
                execute format('alter table %I force row level security', t);
                execute format('create policy tenant_isolation on %I using (company_id = app_company_id() and app_can_see_project(project_id)) '
                                   || 'with check (company_id = app_company_id() and app_can_see_project(project_id))', t);
            end loop;
    end
$$;
