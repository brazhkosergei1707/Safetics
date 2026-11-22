package com.safetics.tasks;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    private static final String SELECT = """
            select id, code, name, statuses, fields, closing_fields, subject_kind, sync_service, children_block_close,
                   template_key, archived_at
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
                                               sync_service, children_block_close, template_key)
                        values (?, ?, cast(? as jsonb), cast(? as jsonb), cast(? as jsonb), cast(? as jsonb), ?, ?, ?, ?)
                        returning id
                        """)
                .params(TenantContext.requireCompanyId(), request.code(), json.writeValueAsString(request.name()),
                        json.writeValueAsString(request.statuses()), json.writeValueAsString(fields(request.fields())),
                        json.writeValueAsString(fields(request.closingFields())), subject(request),
                        Boolean.TRUE.equals(request.syncService()), Boolean.TRUE.equals(request.childrenBlockClose()),
                        templateKey)
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
                            children_block_close = ?, updated_at = now()
                        where id = ?
                        """)
                .params(json.writeValueAsString(request.name()), json.writeValueAsString(request.statuses()),
                        json.writeValueAsString(fields(request.fields())), json.writeValueAsString(fields(request.closingFields())),
                        subject(request), Boolean.TRUE.equals(request.syncService()),
                        Boolean.TRUE.equals(request.childrenBlockClose()), id)
                .update();
        // Категорія статусу могла змінитися — переносимо в задачі
        for (TaskStatusDefinition status : request.statuses()) {
            db.sql("update task set status_category = ? where task_type_id = ? and status = ? and status_category <> ?")
                    .params(status.category(), id, status.code(), status.category()).update();
        }
        audit.record("task_type", id, "update", Map.of("code", request.code()));
        return get(id);
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
        AssetTypeService.validateFields(fields(request.fields()), errors, "fields");
        AssetTypeService.validateFields(fields(request.closingFields()), errors, "closingFields");
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
    }

    private static List<FieldDefinition> fields(List<FieldDefinition> fields) {
        return fields == null ? List.of() : fields;
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
                rs.getString("template_key"),
                rs.getObject("archived_at") != null);
    }
}
