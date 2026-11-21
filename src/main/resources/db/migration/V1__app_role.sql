-- Роль, під якою працює застосунок. Без SUPERUSER і BYPASSRLS: Row-Level Security діє завжди.
-- Ім'я і пароль приходять із плейсхолдерів Flyway (application.yml / змінні оточення).
do $$
begin
    if not exists (select 1 from pg_roles where rolname = '${appdbuser}') then
        create role ${appdbuser} login password '${appdbpassword}' nosuperuser nobypassrls nocreatedb nocreaterole;
    end if;
end
$$;

grant usage on schema public to ${appdbuser};

-- Усі таблиці й послідовності, які створять наступні міграції, одразу доступні застосунку.
-- Обмеження (наприклад, журнал лише на додавання) задаються в міграції конкретної таблиці.
alter default privileges in schema public
    grant select, insert, update, delete on tables to ${appdbuser};
alter default privileges in schema public
    grant usage, select on sequences to ${appdbuser};
alter default privileges in schema public
    grant execute on functions to ${appdbuser};
