package com.safetics.reports;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
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
import com.safetics.reports.ReportCatalogDtos.ActivityCodeRequest;
import com.safetics.reports.ReportCatalogDtos.ActivityCodeView;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Коди операцій журналу часу: один перелік на компанію (шаблон галузі + власні коди). Код, що вже
 * використано, не видаляється — лише архівується.
 */
@Service
public class ActivityCodeService {

    static final Set<String> CATEGORIES = Set.of("productive", "contractor_npt", "client_standby", "other");

    private static final TypeReference<Map<String, String>> NAME = new TypeReference<>() {
    };
    private static final String SELECT =
            "select id, code, name, category, billable, sort_order, template_key, archived_at from activity_code";

    private final JdbcClient db;
    private final JsonMapper json;
    private final AuditService audit;

    public ActivityCodeService(JdbcClient db, JsonMapper json, AuditService audit) {
        this.db = db;
        this.json = json;
        this.audit = audit;
    }

    public List<ActivityCodeView> list(boolean includeArchived) {
        return db.sql(SELECT + (includeArchived ? "" : " where archived_at is null") + " order by sort_order, code")
                .query(this::map).list();
    }

    public ActivityCodeView get(UUID id) {
        return db.sql(SELECT + " where id = ?").param(id).query(this::map).optional().orElseThrow(ApiException::notFound);
    }

    public Optional<ActivityCodeView> findByCode(String code) {
        return db.sql(SELECT + " where code = ?").param(code).query(this::map).optional();
    }

    public ActivityCodeView create(ActivityCodeRequest request, String templateKey) {
        validate(request);
        if (findByCode(request.code()).isPresent()) {
            throw ApiException.validation("code", "duplicate");
        }
        UUID id = db.sql("""
                        insert into activity_code (company_id, code, name, category, billable, sort_order, template_key)
                        values (?, ?, cast(? as jsonb), ?, ?, ?, ?)
                        returning id
                        """)
                .params(TenantContext.requireCompanyId(), request.code(), json.writeValueAsString(request.name()),
                        request.category(), billable(request), request.sortOrder() == null ? 0 : request.sortOrder(),
                        templateKey)
                .query(UUID.class).single();
        audit.record("activity_code", id, "create", Map.of("code", request.code()));
        return get(id);
    }

    public ActivityCodeView update(UUID id, ActivityCodeRequest request) {
        ActivityCodeView existing = get(id);
        if (!existing.code().equals(request.code())) {
            throw ApiException.validation("code", "immutable");
        }
        validate(request);
        db.sql("""
                        update activity_code set name = cast(? as jsonb), category = ?, billable = ?, sort_order = ?,
                                                 updated_at = now()
                        where id = ?
                        """)
                .params(json.writeValueAsString(request.name()), request.category(), billable(request),
                        request.sortOrder() == null ? existing.sortOrder() : request.sortOrder(), id)
                .update();
        audit.record("activity_code", id, "update", Map.of("code", request.code()));
        return get(id);
    }

    public ActivityCodeView setArchived(UUID id, boolean archived) {
        ActivityCodeView existing = get(id);
        if (existing.archived() == archived) {
            return existing;
        }
        db.sql("update activity_code set archived_at = " + (archived ? "now()" : "null") + ", updated_at = now() where id = ?")
                .param(id).update();
        audit.record("activity_code", id, archived ? "archive" : "restore", Map.of("code", existing.code()));
        return get(id);
    }

    /** Продуктивний час і простій з вини замовника оплачуються, решта — ні. */
    static boolean defaultBillable(String category) {
        return "productive".equals(category) || "client_standby".equals(category);
    }

    private static boolean billable(ActivityCodeRequest request) {
        return request.billable() != null ? request.billable() : defaultBillable(request.category());
    }

    private static void validate(ActivityCodeRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (!request.code().matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,29}")) {
            errors.put("code", "invalid_format");
        }
        if (request.name().isEmpty() || request.name().values().stream().allMatch(v -> v == null || v.isBlank())) {
            errors.put("name", "required");
        }
        if (!CATEGORIES.contains(request.category())) {
            errors.put("category", "invalid_option");
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
    }

    private ActivityCodeView map(ResultSet rs, int rowNum) throws SQLException {
        return new ActivityCodeView(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                json.readValue(rs.getString("name"), NAME),
                rs.getString("category"),
                rs.getBoolean("billable"),
                rs.getInt("sort_order"),
                rs.getString("template_key"),
                rs.getObject("archived_at") != null);
    }
}
