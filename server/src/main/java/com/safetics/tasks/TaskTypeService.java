package com.safetics.tasks;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.safetics.assets.AssetTypeService;
import com.safetics.assets.FieldDefinition;
import com.safetics.core.audit.AuditService;
import com.safetics.core.tenancy.TenantContext;
import com.safetics.core.web.ApiException;
import com.safetics.tasks.TaskDtos.TaskRule;
import com.safetics.tasks.TaskDtos.TaskStatusDefinition;
import com.safetics.tasks.TaskDtos.TaskTypeRequest;
import com.safetics.tasks.TaskDtos.TaskTypeView;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Типи задач: статуси за категоріями (перший — для нової задачі, категорія new; хоча б один done),
 * поля, форма закриття, предмет, синхронізація з послугою.
 */
@Service
public class TaskTypeService {

    static final Set<String> CATEGORIES = Set.of("new", "active", "done", "cancelled");
    static final Set<String> SUBJECTS = Set.of("none", "service", "asset");

    private static final TypeReference<List<FieldDefinition>> FIELDS = new TypeReference<>() {
    };
    private static final TypeReference<List<TaskStatusDefinition>> STATUSES = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, String>> NAME = new TypeReference<>() {
    };
    private static final TypeReference<List<TaskRule>> RULES = new TypeReference<>() {
    };

    static final Set<String> TRIGGERS = Set.of("created", "status_category", "shift_created");
    static final Set<String> ASSIGN_TO = Set.of("supervisor", "storekeeper", "parent_assignee", "author", "shift_lead", "none");
    static final Set<String> AUTO_CREATE = Set.of("none", "primary_assignment");

    private static final String SELECT = """
            select id, code, name, statuses, fields, closing_fields, subject_kind, sync_service, children_block_close,
                   rules, auto_create, template_key, archived_at
            from task_type
            """;

    private final JdbcClient db;
    private final JsonMapper json;
    private final AuditService audit;

    public TaskTypeService(JdbcClient db, JsonMapper json, AuditService audit) {
        this.db = db;
        this.json = json;
        this.audit = audit;
    }

    public List<TaskTypeView> list(boolean includeArchived) {
        return db.sql(SELECT + (includeArchived ? "" : " where archived_at is null") + " order by code")
                .query(this::map).list();
    }

    public TaskTypeView get(UUID id) {
        return find(id).orElseThrow(ApiException::notFound);
    }

    public Optional<TaskTypeView> find(UUID id) {
        return db.sql(SELECT + " where id = ?").param(id).query(this::map).optional();
    }

    public Optional<TaskTypeView> findByCode(String code) {
        return db.sql(SELECT + " where code = ?").param(code).query(this::map).optional();
    }

    public TaskTypeView create(TaskTypeRequest request, String templateKey) {
        validate(request);
        if (findByCode(request.code()).isPresent()) {
            throw ApiException.validation("code", "duplicate");
        }
        UUID id = db.sql("""
                        insert into task_type (company_id, code, name, statuses, fields, closing_fields, subject_kind,
                                               sync_service, children_block_close, rules, auto_create, template_key)
                        values (?, ?, cast(? as jsonb), cast(? as jsonb), cast(? as jsonb), cast(? as jsonb), ?, ?, ?,
                                cast(? as jsonb), ?, ?)
                        returning id
                        """)
                .params(TenantContext.requireCompanyId(), request.code(), json.writeValueAsString(request.name()),
                        json.writeValueAsString(request.statuses()), json.writeValueAsString(fields(request.fields())),
                        json.writeValueAsString(fields(request.closingFields())), subject(request),
                        Boolean.TRUE.equals(request.syncService()), Boolean.TRUE.equals(request.childrenBlockClose()),
                        json.writeValueAsString(rules(request.rules())), autoCreate(request), templateKey)
                .query(UUID.class).single();
        audit.record("task_type", id, "create", Map.of("code", request.code()));
        return get(id);
    }

    /** Статуси, що вже використовуються задачами, прибрати не можна. */
    public TaskTypeView update(UUID id, TaskTypeRequest request) {
        TaskTypeView existing = get(id);
        if (!existing.code().equals(request.code())) {
            throw ApiException.validation("code", "immutable");
        }
        validate(request);
        if (!"service".equals(subject(request)) || !Boolean.TRUE.equals(request.syncService())) {
            Long linked = db.sql("select count(*) from service_type where task_type_id = ?").param(id).query(Long.class).single();
            if (linked > 0) {
                throw ApiException.validation("subjectKind", "linked_to_service_type");
            }
        }
        Set<String> codes = new HashSet<>();
        request.statuses().forEach(s -> codes.add(s.code()));
        List<String> used = db.sql("select distinct status from task where task_type_id = ?").param(id)
                .query(String.class).list();
        for (String status : used) {
            if (!codes.contains(status)) {
                throw ApiException.validation("statuses", "status_in_use");
            }
        }
        db.sql("""
                        update task_type
                        set name = cast(? as jsonb), statuses = cast(? as jsonb), fields = cast(? as jsonb),
                            closing_fields = cast(? as jsonb), subject_kind = ?, sync_service = ?,
                            children_block_close = ?, rules = cast(? as jsonb), auto_create = ?, updated_at = now()
                        where id = ?
                        """)
                .params(json.writeValueAsString(request.name()), json.writeValueAsString(request.statuses()),
                        json.writeValueAsString(fields(request.fields())), json.writeValueAsString(fields(request.closingFields())),
                        subject(request), Boolean.TRUE.equals(request.syncService()),
                        Boolean.TRUE.equals(request.childrenBlockClose()), json.writeValueAsString(rules(request.rules())),
                        autoCreate(request), id)
                .update();
        // Категорія статусу могла змінитися — переносимо в задачі
        for (TaskStatusDefinition status : request.statuses()) {
            db.sql("update task set status_category = ? where task_type_id = ? and status = ? and status_category <> ?")
                    .params(status.category(), id, status.code(), status.category()).update();
        }
        audit.record("task_type", id, "update", Map.of("code", request.code()));
        return get(id);
    }

    /**
     * Парний тип головної задачі для нового типу послуги: код perform_<код послуги>, назва
     * «Виконати <назва послуги>» за мовами, стандартні статуси, предмет — послуга, синхронізація.
     * Якщо такий код уже є з предметом «послуга» — повертає його.
     */
    public TaskTypeView createPerformType(String serviceCode, Map<String, String> serviceName, String templateKey) {
        String base = ("perform_" + serviceCode);
        base = base.length() > 50 ? base.substring(0, 50) : base;
        String code = base;
        for (int i = 2; ; i++) {
            Optional<TaskTypeView> existing = findByCode(code);
            if (existing.isEmpty()) {
                break;
            }
            if ("service".equals(existing.get().subjectKind()) && !existing.get().archived()) {
                return existing.get();
            }
            String suffix = "_" + i;
            code = (base.length() + suffix.length() > 50 ? base.substring(0, 50 - suffix.length()) : base) + suffix;
        }
        Map<String, String> name = new LinkedHashMap<>();
        serviceName.forEach((language, value) -> {
            if (value != null && !value.isBlank()) {
                name.put(language, PERFORM.getOrDefault(language, PERFORM.get("en")) + " " + lowerFirst(value.trim()));
            }
        });
        return create(new TaskTypeRequest(code, name, standardStatuses(), List.of(), List.of(), "service", true, false,
                List.of(), "none"), templateKey);
    }

    private static final Map<String, String> PERFORM = Map.of("en", "Perform", "uk", "Виконати", "pt", "Executar");

    private static String lowerFirst(String value) {
        return value.isEmpty() ? value : value.substring(0, 1).toLowerCase(java.util.Locale.ROOT) + value.substring(1);
    }

    /** Нова → в роботі → виконано; скасовано. */
    static List<TaskStatusDefinition> standardStatuses() {
        return List.of(
                new TaskStatusDefinition("new", Map.of("en", "New", "uk", "Нова", "pt", "Nova"), "new", "default"),
                new TaskStatusDefinition("in_progress", Map.of("en", "In progress", "uk", "В роботі", "pt", "Em andamento"),
                        "active", "blue"),
                new TaskStatusDefinition("done", Map.of("en", "Done", "uk", "Виконано", "pt", "Concluída"), "done", "green"),
                new TaskStatusDefinition("cancelled", Map.of("en", "Cancelled", "uk", "Скасовано", "pt", "Cancelada"),
                        "cancelled", null));
    }

    public TaskTypeView setArchived(UUID id, boolean archived) {
        TaskTypeView existing = get(id);
        if (existing.archived() == archived) {
            return existing;
        }
        db.sql("update task_type set archived_at = " + (archived ? "now()" : "null") + ", updated_at = now() where id = ?")
                .param(id).update();
        audit.record("task_type", id, archived ? "archive" : "restore", Map.of("code", existing.code()));
        return get(id);
    }

    static void validate(TaskTypeRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (!request.code().matches("[a-z][a-z0-9_]{1,49}")) {
            errors.put("code", "invalid_format");
        }
        if (request.name().isEmpty() || request.name().values().stream().allMatch(v -> v == null || v.isBlank())) {
            errors.put("name", "required");
        }
        if (request.subjectKind() != null && !SUBJECTS.contains(request.subjectKind())) {
            errors.put("subjectKind", "invalid_option");
        }
        if (Boolean.TRUE.equals(request.syncService()) && !"service".equals(request.subjectKind())) {
            errors.put("syncService", "requires_service_subject");
        }
        List<TaskStatusDefinition> statuses = request.statuses();
        if (statuses.isEmpty()) {
            errors.put("statuses", "required");
        } else {
            Set<String> codes = new HashSet<>();
            for (int i = 0; i < statuses.size(); i++) {
                TaskStatusDefinition s = statuses.get(i);
                if (s.code() == null || !s.code().matches("[a-z][a-z0-9_]{0,49}")) {
                    errors.put("statuses[" + i + "].code", "invalid_format");
                } else if (!codes.add(s.code())) {
                    errors.put("statuses[" + i + "].code", "duplicate");
                }
                if (!CATEGORIES.contains(s.category())) {
                    errors.put("statuses[" + i + "].category", "invalid_option");
                }
            }
            if (!"new".equals(statuses.getFirst().category())) {
                errors.put("statuses[0].category", "first_must_be_new");
            }
            if (statuses.stream().noneMatch(s -> "done".equals(s.category()))) {
                errors.put("statuses", "done_required");
            }
        }
        validateRules(rules(request.rules()), errors);
        if (!AUTO_CREATE.contains(autoCreate(request))) {
            errors.put("autoCreate", "invalid_option");
        } else if ("primary_assignment".equals(autoCreate(request)) && !"asset".equals(request.subjectKind())) {
            errors.put("autoCreate", "requires_asset_subject");
        }
        AssetTypeService.validateFields(fields(request.fields()), errors, "fields");
        AssetTypeService.validateFields(fields(request.closingFields()), errors, "closingFields");
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
    }

    /**
     * Правила: відомий тригер (і категорія для status_category), код типу підзадачі, роль виконавця,
     * строк 0–3650 днів, blockedBy — лише на попереднє правило з тим самим тригером. Чи існує тип
     * підзадачі, не перевіряється: у шаблоні типи встановлюються по черзі; відсутній тип пропускається
     * під час виконання із записом в історії.
     */
    static void validateRules(List<TaskRule> rules, Map<String, String> errors) {
        for (int i = 0; i < rules.size(); i++) {
            TaskRule rule = rules.get(i);
            String prefix = "rules[" + i + "].";
            if (!TRIGGERS.contains(rule.trigger())) {
                errors.put(prefix + "trigger", "invalid_option");
            }
            if ("status_category".equals(rule.trigger())
                    && (rule.category() == null || !Set.of("active", "done", "cancelled").contains(rule.category()))) {
                errors.put(prefix + "category", "required");
            }
            if (rule.action() != null && !"create_subtask".equals(rule.action())) {
                errors.put(prefix + "action", "invalid_option");
            }
            if (rule.taskType() == null || !rule.taskType().matches("[a-z][a-z0-9_]{1,49}")) {
                errors.put(prefix + "taskType", "invalid_format");
            }
            if (rule.assignTo() != null && !ASSIGN_TO.contains(rule.assignTo())) {
                errors.put(prefix + "assignTo", "invalid_option");
            }
            if (rule.dueInDays() != null && (rule.dueInDays() < 0 || rule.dueInDays() > 3650)) {
                errors.put(prefix + "dueInDays", "too_large");
            }
            if (rule.blockedBy() != null) {
                int ref = rule.blockedBy();
                if (ref < 0 || ref >= i || !Objects.equals(rules.get(ref).trigger(), rule.trigger())
                        || !Objects.equals(rules.get(ref).category(), rule.category())) {
                    errors.put(prefix + "blockedBy", "invalid_option");
                }
            }
        }
    }

    private static List<TaskRule> rules(List<TaskRule> rules) {
        return rules == null ? List.of() : rules;
    }

    private static List<FieldDefinition> fields(List<FieldDefinition> fields) {
        return fields == null ? List.of() : fields;
    }

    private static String autoCreate(TaskTypeRequest request) {
        return request.autoCreate() == null || request.autoCreate().isBlank() ? "none" : request.autoCreate();
    }

    /** Тип задачі «Бурова на проєкті» (створюється при призначенні основного обладнання), якщо є. */
    public Optional<TaskTypeView> forPrimaryAssignment() {
        return db.sql(SELECT + " where auto_create = 'primary_assignment' and archived_at is null order by created_at limit 1")
                .query(this::map).optional();
    }

    private static String subject(TaskTypeRequest request) {
        return request.subjectKind() == null || request.subjectKind().isBlank() ? "none" : request.subjectKind();
    }

    private TaskTypeView map(ResultSet rs, int rowNum) throws SQLException {
        return new TaskTypeView(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                json.readValue(rs.getString("name"), NAME),
                json.readValue(rs.getString("statuses"), STATUSES),
                json.readValue(rs.getString("fields"), FIELDS),
                json.readValue(rs.getString("closing_fields"), FIELDS),
                rs.getString("subject_kind"),
                rs.getBoolean("sync_service"),
                rs.getBoolean("children_block_close"),
                json.readValue(rs.getString("rules"), RULES),
                rs.getString("auto_create"),
                rs.getString("template_key"),
                rs.getObject("archived_at") != null);
    }
}
