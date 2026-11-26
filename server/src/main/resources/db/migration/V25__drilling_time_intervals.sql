-- Журнал часу й інтервали буріння об'єднано: рядок часу з кодом «Буріння» має рівно один інтервал.

-- Код операції «Буріння (з інтервалами)»: рядок часу з цим кодом вимагає задачу свердловини й інтервал
alter table activity_code add column drilling boolean not null default false;
update activity_code set drilling = true where code = 'DRL';

-- Інтервал належить рядку часу (один до одного). Старі інтервали без рядка лишаються (time_id порожній).
alter table shift_report_interval add column time_id uuid references shift_report_time (id) on delete cascade;
create unique index shift_report_interval_time_uq on shift_report_interval (time_id) where time_id is not null;

-- Наявні інтервали — до рядків буріння тієї ж свердловини в тому ж рапорті, по порядку
with times as (
    select t.id, t.report_id, tk.service_id,
           row_number() over (partition by t.report_id, tk.service_id order by t.start_minute, t.sort_order) as n
    from shift_report_time t
             join activity_code ac on ac.id = t.activity_code_id and ac.drilling
             join task tk on tk.id = t.task_id
    where tk.service_id is not null
),
     intervals as (
         select i.id, i.report_id, i.service_id,
                row_number() over (partition by i.report_id, i.service_id order by i.sort_order) as n
         from shift_report_interval i
     )
update shift_report_interval si
set time_id = t.id
from intervals i
         join times t on t.report_id = i.report_id and t.service_id = i.service_id and t.n = i.n
where si.id = i.id;
