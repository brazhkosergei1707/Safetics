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

import com.safetics.assets.AssetTypeService;
import com.safetics.assets.FieldDefinition;
import com.safetics.core.audit.AuditService;
import com.safetics.core.tenancy.TenantContext;
import com.safetics.core.web.ApiException;
import com.safetics.services.ServiceDtos.ServiceTypeRequest;
import com.safetics.services.ServiceDtos.ServiceTypeView;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Типи послуг компанії: поля (як у типу обладнання). Шаблон галузі дає готові, компанія змінює. */
@Service
public class ServiceTypeService {

    private static final TypeReference<List<FieldDefinition>> FIELDS = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, String>> NAME = new TypeReference<>() {
    };

    private static final String SELECT = "select id, code, name, fields, template_key, archived_at from service_type";

    private final JdbcClient db;
    private final JsonMapper json;
    private final AuditService audit;

    public ServiceTypeService(JdbcClient db, JsonMapper json, AuditService audit) {
        this.db = db;
        this.json = json;
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

    public ServiceTypeView create(ServiceTypeRequest request, String templateKey) {
        validate(request);
        if (findByCode(request.code()).isPresent()) {
            throw ApiException.validation("code", "duplicate");
        }
        UUID id = db.sql("""
                        insert into service_type (company_id, code, name, fields, template_key)
                        values (?, ?, cast(? as jsonb), cast(? as jsonb), ?)
                        returning id
                        """)
                .params(TenantContext.requireCompanyId(), request.code(), json.writeValueAsString(request.name()),
                        json.writeValueAsString(request.fields()), templateKey)
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
        db.sql("update service_type set name = cast(? as jsonb), fields = cast(? as jsonb), updated_at = now() where id = ?")
                .params(json.writeValueAsString(request.name()), json.writeValueAsString(request.fields()), id)
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

    private static void validate(ServiceTypeRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (!request.code().matches("[a-z][a-z0-9_]{1,49}")) {
            errors.put("code", "invalid_format");
        }
        if (request.name().isEmpty() || request.name().values().stream().allMatch(v -> v == null || v.isBlank())) {
            errors.put("name", "required");
        }
        AssetTypeService.validateFields(request.fields(), errors);
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
    }

    private ServiceTypeView map(ResultSet rs, int rowNum) throws SQLException {
        return new ServiceTypeView(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                json.readValue(rs.getString("name"), NAME),
                json.readValue(rs.getString("fields"), FIELDS),
                rs.getString("template_key"),
                rs.getObject("archived_at") != null);
    }
}
