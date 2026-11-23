-- Крок 5е: виконавцями керують менеджери. Адмін і керівник більше не мають personnel.manage
-- (екран «Користувачі» — лише офісні ролі, «Виконавці» — менеджер і головний менеджер).
delete from role_permission
where permission_code = 'personnel.manage'
  and role_id in (select id from role where company_id is null and code in ('admin', 'director'));
