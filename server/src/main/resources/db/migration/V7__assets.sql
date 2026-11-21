-- Об'єкти обслуговування: свердловини, бурові установки, авто, згодом — квартири, насоси, авто клієнтів.
-- Тип об'єкта задає його поля (fields), статуси (statuses) і які типи можуть бути вкладені (allowed_child_types).
-- Значення полів лежать в asset.attributes (jsonb) і перевіряються за схемою типу в коді (AttributeValidator).

create table asset_type (
    id                  uuid primary key default gen_random_uuid(),
    company_id          uuid        not null references company (id),
    code                text        not null check (code ~ '^[a-z][a-z0-9_]{1,49}$'),
    name                jsonb       not null,                -- {"en": "...", "uk": "..."}
    fields              jsonb       not null default '[]',   -- [FieldDefinition]
    statuses            jsonb       not null default '[]',   -- [StatusDefinition], перший — статус за замовчуванням
    allowed_child_types jsonb       not null default '[]',   -- ["pump", ...] коди типів
    template_key        text,                                -- з якого галузевого шаблону встановлено
    archived_at         timestamptz,
    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now(),
    constraint asset_type_code_unique unique (company_id, code),
    constraint asset_type_id_company_unique unique (id, company_id)
);

create table asset (
    id            uuid primary key default gen_random_uuid(),
    company_id    uuid        not null references company (id),
    asset_type_id uuid        not null,
    parent_id     uuid,
    code          text        not null check (length(trim(code)) between 1 and 100), -- номер свердловини, інв. номер
    name          text,
    status        text        not null,
    attributes    jsonb       not null default '{}',
    version       integer     not null default 1,           -- оптимістичне блокування (офлайн-правки)
    archived_at   timestamptz,
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now(),
    -- Складені ключі: тип і батьківський об'єкт можуть бути лише з тієї ж компанії
    foreign key (asset_type_id, company_id) references asset_type (id, company_id),
    foreign key (parent_id, company_id) references asset (id, company_id),
    constraint asset_id_company_unique unique (id, company_id),
    constraint asset_code_unique unique (company_id, asset_type_id, code),
    constraint asset_not_own_parent check (parent_id is null or parent_id <> id)
);

create index asset_company_type_idx on asset (company_id, asset_type_id) where archived_at is null;
create index asset_parent_idx on asset (parent_id) where parent_id is not null;

alter table asset_type enable row level security;
alter table asset_type force row level security;
create policy tenant_isolation on asset_type
    using (company_id = app_company_id())
    with check (company_id = app_company_id());

alter table asset enable row level security;
alter table asset force row level security;
create policy tenant_isolation on asset
    using (company_id = app_company_id())
    with check (company_id = app_company_id());

-- Дозволи на об'єкти. Налаштування типів — наявний catalog.manage (власник, адмін).
insert into permission (code, description) values
    ('asset.view', 'Об''єкти: перегляд'),
    ('asset.edit', 'Об''єкти: створення, редагування, архівування');

insert into role_permission (role_id, permission_code)
select r.id, p.code
from role r
         cross join (values ('asset.view'), ('asset.edit')) as p(code)
where r.company_id is null
  and r.code in ('owner', 'admin', 'manager', 'worker');
