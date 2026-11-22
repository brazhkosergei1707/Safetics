package com.safetics.personnel;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.safetics.core.audit.AuditService;
import com.safetics.core.tenancy.TenantContext;
import com.safetics.core.web.ApiException;
import com.safetics.personnel.PersonnelDtos.PositionRequest;
import com.safetics.personnel.PersonnelDtos.PositionView;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Довідник посад компанії. Галузевий шаблон дає готовий список, компанія його змінює. */
@Service
public class PositionService {

    static final Set<String> PROJECT_ROLES = Set.of("supervisor", "storekeeper", "none");

    private static final TypeReference<Map<String, String>> NAME = new TypeReference<>() {
    };

    private static final String SELECT = """
            select id, code, name, project_role, supervisor_can_add, archived_at
            from position
            """;

    private final JdbcClient db;
    private final JsonMapper json;
    private final AuditService audit;

    public PositionService(JdbcClient db, JsonMapper json, AuditService audit) {
        this.db = db;
        this.json = json;
        this.audit = audit;
    }

    public List<PositionView> list(boolean includeArchived) {
        return db.sql(SELECT + (includeArchived ? "" : " where archived_at is null") + " order by code")
                .query(this::map).list();
    }

    public PositionView get(UUID id) {
        return find(id).orElseThrow(ApiException::notFound);
    }

    public Optional<PositionView> find(UUID id) {
        return db.sql(SELECT + " where id = ?").param(id).query(this::map).optional();
    }

    public Optional<PositionView> findByCode(String code) {
        return db.sql(SELECT + " where code = ?").param(code).query(this::map).optional();
    }

    public PositionView create(PositionRequest request, String templateKey) {
        String code = request.code().trim();
        if (!code.matches("[a-z][a-z0-9_]{1,49}")) {
            throw ApiException.validation("code", "invalid_format");
        }
        UUID id = db.sql("""
                        insert into position (company_id, code, name, project_role, supervisor_can_add, template_key)
                        values (?, ?, cast(? as jsonb), ?, ?, ?)
                        returning id
                        """)
                .params(TenantContext.requireCompanyId(), code, name(request), projectRole(request),
                        Boolean.TRUE.equals(request.supervisorCanAdd()), templateKey)
                .query(UUID.class).single();
        audit.record("position", id, "create", Map.of("code", code));
        return get(id);
    }

    public PositionView update(UUID id, PositionRequest request) {
        PositionView existing = get(id);
        db.sql("""
                        update position set name = cast(? as jsonb), project_role = ?, supervisor_can_add = ?, updated_at = now()
                        where id = ?
                        """)
                .params(name(request), projectRole(request), Boolean.TRUE.equals(request.supervisorCanAdd()), id)
                .update();
        PositionView saved = get(id);
        audit.record("position", id, "update", Map.of(
                "before", Map.of("projectRole", existing.projectRole(), "supervisorCanAdd", existing.supervisorCanAdd()),
                "after", Map.of("projectRole", saved.projectRole(), "supervisorCanAdd", saved.supervisorCanAdd())));
        return saved;
    }

    public PositionView setArchived(UUID id, boolean archived) {
        PositionView existing = get(id);
        if (existing.archived() == archived) {
            return existing;
        }
        db.sql("update position set archived_at = " + (archived ? "now()" : "null") + ", updated_at = now() where id = ?")
                .param(id).update();
        audit.record("position", id, archived ? "archive" : "restore", null);
        return get(id);
    }

    private String name(PositionRequest request) {
        if (request.name().isEmpty() || request.name().values().stream().allMatch(v -> v == null || v.isBlank())) {
            throw ApiException.validation("name", "required");
        }
        return json.writeValueAsString(request.name());
    }

    private static String projectRole(PositionRequest request) {
        String role = request.projectRole() == null || request.projectRole().isBlank() ? "none" : request.projectRole();
        if (!PROJECT_ROLES.contains(role)) {
            throw ApiException.validation("projectRole", "invalid_option");
        }
        return role;
    }

    private PositionView map(ResultSet rs, int rowNum) throws SQLException {
        return new PositionView(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                json.readValue(rs.getString("name"), NAME),
                rs.getString("project_role"),
                rs.getBoolean("supervisor_can_add"),
                rs.getObject("archived_at") != null);
    }
}
