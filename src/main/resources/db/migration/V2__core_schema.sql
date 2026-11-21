-- Ядро: компанії, користувачі, ролі, дозволи, членство, журнал змін.
-- Правило: кожна таблиця компанії має company_id і політику RLS (V3).
-- Глобальні таблиці без company_id: app_user (один акаунт на людину), permission (довідник).

create table company (
    id              uuid primary key default gen_random_uuid(),
    name            text        not null,
    legal_code      text,                                   -- ЄДРПОУ / РНОКПП / іноземний реєстраційний номер
    phone           text,
    plan            text        not null default 'pilot',
    template_key    text,                                   -- галузевий шаблон, напр. 'exploration-drilling'
    enabled_modules text[]      not null default '{}',
    status          text        not null default 'active'
        check (status in ('active', 'suspended', 'archived')),
    created_at      timestamptz not null default now()
);

create table app_user (
    id            uuid primary key default gen_random_uuid(),
    phone         text        not null unique,
    full_name     text        not null,
    email         text,
    telegram_id   bigint unique,
    password_hash text,
    status        text        not null default 'active'
        check (status in ('active', 'blocked')),
    created_at    timestamptz not null default now()
);

create table permission (
    code        text primary key,                           -- напр. 'stock.write_off'
    description text not null
);

-- Системні ролі: company_id is null, is_system = true. Власні ролі компанії: company_id заданий.
create table role (
    id         uuid primary key default gen_random_uuid(),
    company_id uuid references company (id),
    code       text        not null,
    name       text        not null,
    is_system  boolean     not null default false,
    created_at timestamptz not null default now(),
    constraint role_code_unique unique nulls not distinct (company_id, code),
    constraint role_system_has_no_company check (is_system = (company_id is null)),
    constraint role_id_company_unique unique (id, company_id)
);
create index role_company_idx on role (company_id);

-- company_id копіює company_id ролі: складений ключ не дає прив'язати дозвіл до ролі чужої компанії.
create table role_permission (
    role_id         uuid not null references role (id) on delete cascade,
    permission_code text not null references permission (code),
    company_id      uuid references company (id),
    primary key (role_id, permission_code),
    foreign key (role_id, company_id) references role (id, company_id)
);

create table membership (
    id         uuid primary key default gen_random_uuid(),
    company_id uuid        not null references company (id),
    user_id    uuid        not null references app_user (id),
    role_id    uuid        not null references role (id),
    status     text        not null default 'active'
        check (status in ('active', 'invited', 'disabled')),
    created_at timestamptz not null default now(),
    unique (company_id, user_id)
);
create index membership_user_idx on membership (user_id);

-- Роль у членстві: системна або власна роль тієї ж компанії.
create function check_membership_role() returns trigger
    language plpgsql as
$$
begin
    if not exists (select 1
                   from role r
                   where r.id = new.role_id
                     and (r.company_id is null or r.company_id = new.company_id)) then
        raise exception 'Role % does not belong to company %', new.role_id, new.company_id
            using errcode = 'check_violation';
    end if;
    return new;
end
$$;

create trigger membership_role_check
    before insert or update of role_id, company_id
    on membership
    for each row
execute function check_membership_role();

-- Журнал змін: лише додавання. Застосунок не може змінювати чи видаляти записи.
create table audit_log (
    id         bigint generated always as identity primary key,
    company_id uuid        not null references company (id),
    user_id    uuid references app_user (id),
    entity     text        not null,
    entity_id  text        not null,
    action     text        not null,
    changes    jsonb,
    created_at timestamptz not null default now()
);
create index audit_log_company_created_idx on audit_log (company_id, created_at);

revoke update, delete, truncate on audit_log from ${appdbuser};
