-- Крок 5є: порядок типів обладнання (вкладки), походження типу з бібліотеки, читання бібліотеки.
-- Бібліотека — типи всіх компаній; читаються лише в режимі app.library (вмикає код у межах транзакції),
-- назви компаній не показуються.

alter table asset_type
    add column sort_order     integer not null default 0,
    add column source_type_id uuid;     -- тип іншої компанії, з якого скопійовано (без FK: та компанія може його видалити)

-- Наявний порядок: основне першим, далі за кодом
update asset_type t
set sort_order = o.n
from (select id, row_number() over (partition by company_id order by (category = 'primary') desc, code) as n
      from asset_type) o
where o.id = t.id;

-- Новий тип — у кінець списку компанії
create function asset_type_next_sort() returns trigger
    language plpgsql as
$$
begin
    if new.sort_order = 0 then
        select coalesce(max(sort_order), 0) + 1 into new.sort_order
        from asset_type where company_id = new.company_id;
    end if;
    return new;
end;
$$;

create trigger asset_type_sort_order
    before insert on asset_type
    for each row execute function asset_type_next_sort();

create function app_library() returns boolean
    language sql stable as
$$
select coalesce(current_setting('app.library', true), '') = 'on'
$$;

-- Лише читання і лише в режимі бібліотеки; зміни — як і раніше, тільки своєї компанії
create policy library_read on asset_type
    for select
    using (app_library());
