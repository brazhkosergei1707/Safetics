package com.safetics.services;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.safetics.assets.AssetTypeService;
import com.safetics.assets.FieldDefinition;
import com.safetics.core.audit.AuditService;
import com.safetics.core.tenancy.TenantContext;
import com.safetics.core.web.ApiException;
import com.safetics.services.ServiceDtos.ServiceTypeRequest;
import com.safetics.services.ServiceDtos.ServiceTypeView;
import com.safetics.tasks.TaskDtos.TaskTypeView;
import com.safetics.tasks.TaskTypeService;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Типи послуг компанії: поля (як у типу обладнання) і тип головної задачі. Шаблон галузі дає готові,
 * компанія змінює.
 */
@Service
public class ServiceTypeService {

    private static final TypeReference<List<FieldDefinition>> FIELDS = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, String>> NAME = new TypeReference<>() {
    };

    private static final String SELECT = "select id, code, name, fields, task_type_id, unit, template_key, archived_at from service_type";

    private final JdbcClient db;
    private final JsonMapper json;
    private final TaskTypeService taskTypes;
    private final AuditService audit;

    public ServiceTypeService(JdbcClient db, JsonMapper json, TaskTypeService taskTypes, AuditService audit) {
        this.db = db;
        this.json = json;
        this.taskTypes = taskTypes;
        this.audit = audit;
    }

    public List<ServiceTypeView> list(boolean includeArchived) {
        return db.sql(SELECT + (includeArchived ? "" : " where archived_at is null") + " order by code")
                .query(this::map).list();
    }

    public ServiceTypeView get(UUID id) {
        return find(id).orElseThrow(ApiException::notFound);
    }

    public Optional<ServiceTypeView> find(UUID id) {
        return db.sql(SELECT + " where id = ?").param(id).query(this::map).optional();
    }

    public Optional<ServiceTypeView> findByCode(String code) {
        return db.sql(SELECT + " where code = ?").param(code).query(this::map).optional();
    }

    /** Без taskTypeId — створює парний тип задачі «Виконати …». */
    @Transactional
    public ServiceTypeView create(ServiceTypeRequest request, String templateKey) {
        validate(request);
        if (findByCode(request.code()).isPresent()) {
            throw ApiException.validation("code", "duplicate");
        }
        UUID taskTypeId = request.taskTypeId() != null
                ? checkTaskType(request.taskTypeId())
                : taskTypes.createPerformType(request.code(), request.name(), templateKey).id();
        UUID id = db.sql("""
                        insert into service_type (company_id, code, name, fields, task_type_id, unit, template_key)
                        values (?, ?, cast(? as jsonb), cast(? as jsonb), ?, ?, ?)
                        returning id
                        """)
                .params(TenantContext.requireCompanyId(), request.code(), json.writeValueAsString(request.name()),
                        json.writeValueAsString(request.fields()), taskTypeId, blankToNull(request.unit()), templateKey)
                .query(UUID.class).single();
        audit.record("service_type", id, "create", Map.of("code", request.code()));
        return get(id);
    }

    public ServiceTypeView update(UUID id, ServiceTypeRequest request) {
        ServiceTypeView existing = get(id);
        if (!existing.code().equals(request.code())) {
            throw ApiException.validation("code", "immutable");
        }
        validate(request);
        UUID taskTypeId = request.taskTypeId() == null || request.taskTypeId().equals(existing.taskTypeId())
                ? existing.taskTypeId()
                : checkTaskType(request.taskTypeId());
        if (taskTypeId == null) {
            taskTypeId = taskTypes.createPerformType(existing.code(), request.name(), null).id();
        }
        db.sql("""
                        update service_type set name = cast(? as jsonb), fields = cast(? as jsonb), task_type_id = ?,
                                                unit = ?, updated_at = now()
                        where id = ?
                        """)
                .params(json.writeValueAsString(request.name()), json.writeValueAsString(request.fields()), taskTypeId,
                        blankToNull(request.unit()), id)
                .update();
        audit.record("service_type", id, "update", Map.of("code", request.code()));
        return get(id);
    }

    public ServiceTypeView setArchived(UUID id, boolean archived) {
        ServiceTypeView existing = get(id);
        if (existing.archived() == archived) {
            return existing;
        }
        db.sql("update service_type set archived_at = " + (archived ? "now()" : "null") + ", updated_at = now() where id = ?")
                .param(id).update();
        audit.record("service_type", id, archived ? "archive" : "restore", Map.of("code", existing.code()));
        return get(id);
    }

    /** Тип головної задачі: є, не в архіві, предмет — послуга, синхронізує послугу. */
    private UUID checkTaskType(UUID taskTypeId) {
        TaskTypeView type = taskTypes.find(taskTypeId).orElseThrow(() -> ApiException.validation("taskTypeId", "not_found"));
        if (type.archived()) {
            throw ApiException.validation("taskTypeId", "archived");
        }
        if (!"service".equals(type.subjectKind()) || !type.syncService()) {
            throw ApiException.validation("taskTypeId", "not_service_task_type");
        }
        return type.id();
    }

    private static void validate(ServiceTypeRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (!request.code().matches("[a-z][a-z0-9_]{1,49}")) {
            errors.put("code", "invalid_format");
        }
        if (request.name().isEmpty() || request.name().values().stream().allMatch(v -> v == null || v.isBlank())) {
            errors.put("name", "required");
        }
        AssetTypeService.validateFields(request.fields(), errors);
        if (request.unit() != null && !request.unit().isBlank() && !UNITS.contains(request.unit())) {
            errors.put("unit", "invalid_option");
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
    }

    static final java.util.Set<String> UNITS = java.util.Set.of("m", "m2", "m3", "pcs", "order", "h");

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private ServiceTypeView map(ResultSet rs, int rowNum) throws SQLException {
        return new ServiceTypeView(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                json.readValue(rs.getString("name"), NAME),
                json.readValue(rs.getString("fields"), FIELDS),
                rs.getObject("task_type_id", UUID.class),
                rs.getString("unit"),
                rs.getString("template_key"),
                rs.getObject("archived_at") != null);
    }
}
