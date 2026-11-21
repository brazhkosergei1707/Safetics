-- Токени оновлення сесії. Зберігається лише SHA-256 хеш, сам токен знає тільки клієнт.
-- Таблиця без RLS: на момент оновлення компанія ще невідома, пошук іде за хешем.
-- Кожен токен одноразовий: при оновленні старий відкликається і видається новий.

create table refresh_token (
    id         uuid primary key default gen_random_uuid(),
    user_id    uuid        not null references app_user (id),
    company_id uuid references company (id),
    token_hash text        not null unique,
    expires_at timestamptz not null,
    revoked_at timestamptz,
    created_at timestamptz not null default now()
);

create index refresh_token_user_idx on refresh_token (user_id);
