-- Після входу користувач має побачити список компаній, у яких працює, щоб обрати одну.
-- Читати: поточну компанію або ту, де в користувача є активне членство.
-- Змінювати: лише поточну компанію.

drop policy tenant_isolation on company;

create policy company_select on company
    for select
    using (
        id = app_company_id()
        or exists (select 1
                   from membership m
                   where m.company_id = company.id
                     and m.user_id = app_user_id()
                     and m.status = 'active')
    );

create policy company_insert on company
    for insert
    with check (id = app_company_id());

create policy company_update on company
    for update
    using (id = app_company_id())
    with check (id = app_company_id());

create policy company_delete on company
    for delete
    using (id = app_company_id());
