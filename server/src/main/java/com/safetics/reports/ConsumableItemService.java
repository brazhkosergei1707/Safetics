package com.safetics.reports;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.safetics.core.audit.AuditService;
import com.safetics.core.tenancy.TenantContext;
import com.safetics.core.web.ApiException;
import com.safetics.reports.ReportCatalogDtos.ConsumableItemRequest;
import com.safetics.reports.ReportCatalogDtos.ConsumableItemView;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Позиції витрат (коронка, розширювач, ПММ, буровий розчин…). Пізніше — спільна номенклатура зі складом. */
@Service
public class ConsumableItemService {

    private static final TypeReference<Map<String, String>> NAME = new TypeReference<>() {
    };
    private static final String SELECT =
            "select id, code, name, unit, serial_tracked, template_key, archived_at from consumable_item";

    private final JdbcClient db;
    private final JsonMapper json;
    private final AuditService audit;

    public ConsumableItemService(JdbcClient db, JsonMapper json, AuditService audit) {
        this.db = db;
        this.json = json;
        this.audit = audit;
    }

    public List<ConsumableItemView> list(boolean includeArchived) {
        return db.sql(SELECT + (includeArchived ? "" : " where archived_at is null") + " order by code")
                .query(this::map).list();
    }

    public ConsumableItemView get(UUID id) {
        return db.sql(SELECT + " where id = ?").param(id).query(this::map).optional().orElseThrow(ApiException::notFound);
    }

    public Optional<ConsumableItemView> findByCode(String code) {
        return db.sql(SELECT + " where code = ?").param(code).query(this::map).optional();
    }

    public ConsumableItemView create(ConsumableItemRequest request, String templateKey) {
        validate(request);
        if (findByCode(request.code()).isPresent()) {
            throw ApiException.validation("code", "duplicate");
        }
        UUID id = db.sql("""
                        insert into consumable_item (company_id, code, name, unit, serial_tracked, template_key)
                        values (?, ?, cast(? as jsonb), ?, ?, ?)
                        returning id
                        """)
                .params(TenantContext.requireCompanyId(), request.code(), json.writeValueAsString(request.name()),
                        request.unit().trim(), Boolean.TRUE.equals(request.serialTracked()), templateKey)
                .query(UUID.class).single();
        audit.record("consumable_item", id, "create", Map.of("code", request.code()));
        return get(id);
    }

    public ConsumableItemView update(UUID id, ConsumableItemRequest request) {
        ConsumableItemView existing = get(id);
        if (!existing.code().equals(request.code())) {
            throw ApiException.validation("code", "immutable");
        }
        validate(request);
        db.sql("""
                        update consumable_item set name = cast(? as jsonb), unit = ?, serial_tracked = ?, updated_at = now()
                        where id = ?
                        """)
                .params(json.writeValueAsString(request.name()), request.unit().trim(),
                        Boolean.TRUE.equals(request.serialTracked()), id)
                .update();
        audit.record("consumable_item", id, "update", Map.of("code", request.code()));
        return get(id);
    }

    public ConsumableItemView setArchived(UUID id, boolean archived) {
        ConsumableItemView existing = get(id);
        if (existing.archived() == archived) {
            return existing;
        }
        db.sql("update consumable_item set archived_at = " + (archived ? "now()" : "null") + ", updated_at = now() where id = ?")
                .param(id).update();
        audit.record("consumable_item", id, archived ? "archive" : "restore", Map.of("code", existing.code()));
        return get(id);
    }

    private static void validate(ConsumableItemRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (!request.code().matches("[a-z0-9][a-z0-9_.-]{0,49}")) {
            errors.put("code", "invalid_format");
        }
        if (request.name().isEmpty() || request.name().values().stream().allMatch(v -> v == null || v.isBlank())) {
            errors.put("name", "required");
        }
        if (request.unit().trim().length() > 20) {
            errors.put("unit", "too_long");
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
    }

    private ConsumableItemView map(ResultSet rs, int rowNum) throws SQLException {
        return new ConsumableItemView(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                json.readValue(rs.getString("name"), NAME),
                rs.getString("unit"),
                rs.getBoolean("serial_tracked"),
                rs.getString("template_key"),
                rs.getObject("archived_at") != null);
    }
}
