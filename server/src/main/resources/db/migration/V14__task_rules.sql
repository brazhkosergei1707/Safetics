-- Крок 4б: автоматизація задач — правила «тригер → дія» в типі задачі.
-- rules: [{"trigger": "created" | "status_category", "category": "active|done|cancelled" (для status_category),
--          "action": "create_subtask", "taskType": "<код типу>", "title": "<шаблон назви>",
--          "assignTo": "supervisor|storekeeper|parent_assignee|author|none", "dueInDays": 3,
--          "blockedBy": <номер правила в цьому списку, від 0>}]
alter table task_type add column rules jsonb not null default '[]';
