-- Довідник дозволів і системні ролі за матрицею з 01-specification.md.
-- У коді перевіряється лише код дозволу, ніколи назва ролі.
-- Обмеження «лише свої» (наряди, списання) — це фільтр рядків у сервісах, а не окремі дозволи.

insert into permission (code, description) values
    ('billing.manage',        'Тариф, оплата платформи, видалення компанії'),
    ('company.settings',      'Налаштування компанії'),
    ('staff.manage',          'Працівники і ролі'),
    ('catalog.manage',        'Каталог послуг, ціни, типи об''єктів, форми, підстатуси'),
    ('catalog.view',          'Перегляд каталогу і цін'),
    ('client.view',           'Клієнти і об''єкти: перегляд'),
    ('client.edit',           'Клієнти і об''єкти: створення, редагування'),
    ('client.delete',         'Клієнти: видалення, об''єднання дублікатів'),
    ('request.create',        'Заявки: створення'),
    ('workorder.plan',        'Наряди: планування, призначення, перенесення'),
    ('workorder.execute',     'Наряди: статуси виконання, фото, звіт'),
    ('workorder.discount',    'Знижка в наряді'),
    ('finance.view_cost',     'Собівартість і прибуток'),
    ('stock.view',            'Склад: перегляд залишків'),
    ('stock.receive',         'Склад: надходження'),
    ('stock.write_off',       'Склад: списання'),
    ('stock.adjust',          'Склад: коригування, сторнування'),
    ('stock.transfer',        'Склад: переміщення між складами'),
    ('payment.accept',        'Прийняти оплату'),
    ('audit.view',            'Журнал змін'),
    ('project.manage',        'Проєкти (контракти), ставки, установки, бригади'),
    ('report.view',           'Змінні рапорти: перегляд'),
    ('report.submit',         'Змінні рапорти: заповнення і подання'),
    ('report.approve_site',   'Змінні рапорти: затвердження начальником дільниці'),
    ('report.approve_client', 'Змінні рапорти: затвердження замовником'),
    ('invoice.manage',        'Місячні рахунки замовнику'),
    ('hse.checklist.run',     'HSE: проходження чек-листів'),
    ('hse.incident.report',   'HSE: повідомлення про інцидент'),
    ('hse.manage',            'HSE: шаблони чек-листів, розслідування, коригувальні дії, статистика');

insert into role (company_id, code, name, is_system) values
    (null, 'owner',      'Власник',              true),
    (null, 'admin',      'Адмін',                true),
    (null, 'manager',    'Менеджер',             true),
    (null, 'worker',     'Виконавець',           true),
    (null, 'client_rep', 'Представник замовника', true);

-- Власник: усе, крім затвердження від імені замовника
insert into role_permission (role_id, permission_code)
select r.id, p.code
from role r
         cross join permission p
where r.company_id is null
  and r.code = 'owner'
  and p.code <> 'report.approve_client';

-- Адмін: як власник, без тарифу й оплати платформи
insert into role_permission (role_id, permission_code)
select r.id, p.code
from role r
         cross join permission p
where r.company_id is null
  and r.code = 'admin'
  and p.code not in ('billing.manage', 'report.approve_client');

with grants (role_code, perms) as (values
    ('manager', array [
        'catalog.view', 'client.view', 'client.edit', 'request.create',
        'workorder.plan', 'workorder.execute', 'workorder.discount',
        'stock.view', 'stock.write_off', 'stock.transfer', 'payment.accept',
        'report.view', 'report.submit', 'report.approve_site',
        'hse.checklist.run', 'hse.incident.report']),
    ('worker', array [
        'client.view', 'client.edit', 'request.create', 'workorder.execute',
        'stock.view', 'stock.write_off', 'payment.accept',
        'report.view', 'report.submit',
        'hse.checklist.run', 'hse.incident.report']),
    ('client_rep', array [
        'report.view', 'report.approve_client'])
)
insert into role_permission (role_id, permission_code)
select r.id, unnest(g.perms)
from grants g
         join role r on r.code = g.role_code and r.company_id is null;
