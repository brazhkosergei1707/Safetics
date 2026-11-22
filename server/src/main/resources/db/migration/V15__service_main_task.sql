-- Крок 4в: у кожної послуги є головна задача. Тип послуги пов'язаний з типом задачі (предмет —
-- послуга, синхронізація статусу); створення послуги створює головну задачу з тими самими датами
-- й відповідальним. Статус послуги веде головна задача.

alter table service_type add column task_type_id uuid;
alter table service_type
    add constraint service_type_task_type_fk foreign key (task_type_id, company_id) references task_type (id, company_id);

alter table service add column main_task_id uuid references task (id);
create unique index service_main_task_unique on service (main_task_id) where main_task_id is not null;

-- ---------------------------------------------------------------- системні дії

-- Дії системи від імені користувача, який сам задач не бачить (замовник через портал додає послугу —
-- головна задача створюється автоматично). Вмикається на час транзакції:
--   select set_config('app.system', 'on', true)
create function app_system() returns boolean
    language sql stable as
$$ select coalesce(current_setting('app.system', true), '') = 'on' $$;

drop policy tenant_isolation on task;
create policy tenant_isolation on task
    using (company_id = app_company_id() and app_can_see_project(project_id)
           and ((select app_has_permission('task.view')) or (select app_system())))
    with check (company_id = app_company_id() and app_can_see_project(project_id)
                and ((select app_has_permission('task.view')) or (select app_system())));

drop policy tenant_isolation on task_link;
create policy tenant_isolation on task_link
    using (company_id = app_company_id() and ((select app_has_permission('task.view')) or (select app_system())))
    with check (company_id = app_company_id() and ((select app_has_permission('task.view')) or (select app_system())));

drop policy tenant_isolation on task_history;
create policy tenant_isolation on task_history
    using (company_id = app_company_id() and ((select app_has_permission('task.view')) or (select app_system())))
    with check (company_id = app_company_id() and ((select app_has_permission('task.view')) or (select app_system())));

-- ---------------------------------------------------------------- наявні дані

create function pg_temp.lower_first(value text) returns text
    language sql immutable as
$$ select lower(left(value, 1)) || substr(value, 2) $$;

do
$$
    declare
        st          record;
        sv          record;
        linked_type uuid;
        new_code    text;
        status_def  jsonb;
        next_number bigint;
        task_id     uuid;
        category    text;
    begin
        -- 1. Типи послуг → типи задач: відомі пари з шаблонів, інакше новий тип «Виконати …»
        for st in select * from service_type where task_type_id is null
            loop
                select tt.id
                into linked_type
                from task_type tt
                where tt.company_id = st.company_id
                  and tt.subject_kind = 'service'
                  and tt.code = case st.code
                                    when 'drill_hole' then 'drilling'
                                    when 'water_well' then 'drilling'
                                    when 'furniture_order' then 'order'
                                    when 'work_site' then 'work_stage'
                                    else 'perform_' || st.code end
                limit 1;

                if linked_type is null then
                    new_code := left('perform_' || st.code, 50);
                    insert into task_type (company_id, code, name, statuses, subject_kind, sync_service)
                    values (st.company_id, new_code,
                            jsonb_build_object(
                                    'en', 'Perform ' || pg_temp.lower_first(coalesce(st.name ->> 'en', st.code)),
                                    'uk', 'Виконати ' || pg_temp.lower_first(coalesce(st.name ->> 'uk', st.name ->> 'en', st.code)),
                                    'pt', 'Executar ' || pg_temp.lower_first(coalesce(st.name ->> 'pt', st.name ->> 'en', st.code))),
                            '[{"code": "new", "label": {"en": "New", "uk": "Нова", "pt": "Nova"}, "category": "new", "color": "default"},
                              {"code": "in_progress", "label": {"en": "In progress", "uk": "В роботі", "pt": "Em andamento"}, "category": "active", "color": "blue"},
                              {"code": "done", "label": {"en": "Done", "uk": "Виконано", "pt": "Concluída"}, "category": "done", "color": "green"},
                              {"code": "cancelled", "label": {"en": "Cancelled", "uk": "Скасовано", "pt": "Cancelada"}, "category": "cancelled"}]',
                            'service', true)
                    on conflict (company_id, code) do nothing
                    returning id into linked_type;
                    if linked_type is null then
                        select id into linked_type from task_type where company_id = st.company_id and code = new_code;
                    end if;
                end if;

                update service_type set task_type_id = linked_type where id = st.id;
            end loop;

        -- 2. Послуги → головні задачі: наявна задача цього типу про цю послугу або нова
        for sv in
            select s.*, stt.task_type_id as main_type
            from service s
                     join service_type stt on stt.id = s.service_type_id
            where s.main_task_id is null
            loop
                select t.id
                into task_id
                from task t
                where t.service_id = sv.id
                  and t.task_type_id = sv.main_type
                order by t.number
                limit 1;

                if task_id is null then
                    category := case sv.status
                                    when 'planned' then 'new'
                                    when 'in_progress' then 'active'
                                    when 'completed' then 'done'
                                    else 'cancelled' end;
                    select s
                    into status_def
                    from task_type tt, jsonb_array_elements(tt.statuses) s
                    where tt.id = sv.main_type
                      and s ->> 'category' = category
                    limit 1;
                    if status_def is null then
                        select tt.statuses -> 0 into status_def from task_type tt where tt.id = sv.main_type;
                    end if;

                    insert into task_counter (company_id, last_number)
                    values (sv.company_id, 1)
                    on conflict (company_id) do update set last_number = task_counter.last_number + 1
                    returning last_number into next_number;

                    insert into task (company_id, project_id, task_type_id, number, title, status, status_category,
                                      author_id, service_id, planned_start, planned_end, actual_start, actual_end)
                    values (sv.company_id, sv.project_id, sv.main_type, next_number, sv.code,
                            status_def ->> 'code', status_def ->> 'category', sv.created_by, sv.id,
                            sv.planned_start, sv.planned_end, sv.actual_start, sv.actual_end)
                    returning id into task_id;
                end if;

                update service set main_task_id = task_id where id = sv.id;
            end loop;
    end
$$;
