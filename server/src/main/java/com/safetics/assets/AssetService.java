package com.safetics.assets;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.safetics.assets.AssetDtos.AssetCreateRequest;
import com.safetics.assets.AssetDtos.AssetPage;
import com.safetics.assets.AssetDtos.AssetTypeView;
import com.safetics.assets.AssetDtos.AssetUpdateRequest;
import com.safetics.assets.AssetDtos.AssetView;
import com.safetics.core.audit.AuditService;
import com.safetics.core.tenancy.TenantContext;
import com.safetics.core.web.ApiException;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Об'єкти компанії: створення, зміна, архів, ієрархія (свердловина → насос).
 *
 * <p>Правила: значення полів перевіряються за схемою типу; статус — лише зі списку типу;
 * батьківський тип має дозволяти вкладений тип; цикли в ієрархії заборонені; зміна вимагає
 * актуальну версію (інакше 409 stale_version — хтось змінив об'єкт раніше, наприклад з телефону офлайн).
 */
@Service
public class AssetService {

    public record Filter(UUID typeId, String status, UUID parentId, String query, boolean includeArchived,
                         int limit, int offset) {
    }

    private static final TypeReference<Map<String, Object>> ATTRIBUTES = new TypeReference<>() {
    };

    private static final String SELECT = """
            select a.id, a.asset_type_id, t.code as type_code, a.parent_id, a.code, a.name, a.status,
                   a.attributes, a.version, a.archived_at, a.created_at, a.updated_at
            from asset a
                     join asset_type t on t.id = a.asset_type_id
            """;

    private final JdbcClient db;
    private final JsonMapper json;
    private final AssetTypeService types;
    private final AuditService audit;

    public AssetService(JdbcClient db, JsonMapper json, AssetTypeService types, AuditService audit) {
        this.db = db;
        this.json = json;
        this.types = types;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- читання

    public AssetPage list(Filter filter) {
        StringBuilder where = new StringBuilder(" where 1 = 1");
        List<Object> params = new ArrayList<>();
        if (!filter.includeArchived()) {
            where.append(" and a.archived_at is null");
        }
        if (filter.typeId() != null) {
            where.append(" and a.asset_type_id = ?");
            params.add(filter.typeId());
        }
        if (filter.status() != null && !filter.status().isBlank()) {
            where.append(" and a.status = ?");
            params.add(filter.status());
        }
        if (filter.parentId() != null) {
            where.append(" and a.parent_id = ?");
            params.add(filter.parentId());
        }
        if (filter.query() != null && !filter.query().isBlank()) {
            where.append(" and (a.code ilike ? or a.name ilike ?)");
            String like = "%" + filter.query().trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            params.add(like);
            params.add(like);
        }

        long total = db.sql("select count(*) from asset a" + where).params(params).query(Long.class).single();

        int limit = Math.max(1, Math.min(filter.limit(), 500));
        int offset = Math.max(0, filter.offset());
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(limit);
        pageParams.add(offset);
        List<AssetView> items = db.sql(SELECT + where + " order by t.code, a.code limit ? offset ?")
                .params(pageParams)
                .query(this::map)
                .list();
        return new AssetPage(items, total);
    }

    public AssetView get(UUID id) {
        return find(id).orElseThrow(ApiException::notFound);
    }

    public Optional<AssetView> find(UUID id) {
        return db.sql(SELECT + " where a.id = ?").param(id).query(this::map).optional();
    }

    // ---------------------------------------------------------------- зміни

    public AssetView create(AssetCreateRequest request) {
        AssetTypeView type = types.find(request.typeId())
                .orElseThrow(() -> ApiException.validation("typeId", "not_found"));
        if (type.archived()) {
            throw ApiException.validation("typeId", "archived");
        }
        String status = request.status() == null || request.status().isBlank()
                ? type.statuses().getFirst().code()
                : request.status();
        checkStatus(type, status);
        Map<String, Object> attributes = validAttributes(type, request.attributes());
        if (request.parentId() != null) {
            checkParent(request.parentId(), type, null);
        }

        UUID id = db.sql("""
                        insert into asset (company_id, asset_type_id, parent_id, code, name, status, attributes)
                        values (?, ?, ?, ?, ?, ?, cast(? as jsonb))
                        returning id
                        """)
                .params(TenantContext.requireCompanyId(), type.id(), request.parentId(), request.code().trim(),
                        blankToNull(request.name()), status, json.writeValueAsString(attributes))
                .query(UUID.class)
                .single();
        audit.record("asset", id, "create", Map.of("type", type.code(), "code", request.code().trim()));
        return get(id);
    }

    public AssetView update(UUID id, AssetUpdateRequest request) {
        AssetView existing = get(id);
        if (existing.version() != request.version()) {
            throw ApiException.conflict("stale_version");
        }
        AssetTypeView type = types.get(existing.typeId());
        checkStatus(type, request.status());

        Map<String, Object> attributes = validAttributes(type, request.attributes());
        // Значення полів, яких уже немає в схемі типу, не губляться при редагуванні
        existing.attributes().forEach((key, value) -> {
            boolean inSchema = type.fields().stream().anyMatch(f -> f.key().equals(key));
            if (!inSchema) {
                attributes.putIfAbsent(key, value);
            }
        });

        if (request.parentId() != null && !request.parentId().equals(existing.parentId())) {
            checkParent(request.parentId(), type, id);
        }

        int updated = db.sql("""
                        update asset
                        set parent_id = ?, code = ?, name = ?, status = ?, attributes = cast(? as jsonb),
                            version = version + 1, updated_at = now()
                        where id = ? and version = ?
                        """)
                .params(request.parentId(), request.code().trim(), blankToNull(request.name()), request.status(),
                        json.writeValueAsString(attributes), id, request.version())
                .update();
        if (updated == 0) {
            throw ApiException.conflict("stale_version");
        }

        AssetView saved = get(id);
        Map<String, Object> changes = changes(existing, saved);
        if (!changes.isEmpty()) {
            audit.record("asset", id, "update", changes);
        }
        return saved;
    }

    public AssetView archive(UUID id) {
        AssetView existing = get(id);
        if (existing.archived()) {
            return existing;
        }
        Long activeChildren = db.sql("select count(*) from asset where parent_id = ? and archived_at is null")
                .param(id).query(Long.class).single();
        if (activeChildren > 0) {
            throw ApiException.conflict("has_active_children");
        }
        db.sql("update asset set archived_at = now(), version = version + 1, updated_at = now() where id = ?")
                .param(id).update();
        audit.record("asset", id, "archive", null);
        return get(id);
    }

    public AssetView restore(UUID id) {
        AssetView existing = get(id);
        if (!existing.archived()) {
            return existing;
        }
        if (existing.parentId() != null && get(existing.parentId()).archived()) {
            throw ApiException.conflict("parent_archived");
        }
        db.sql("update asset set archived_at = null, version = version + 1, updated_at = now() where id = ?")
                .param(id).update();
        audit.record("asset", id, "restore", null);
        return get(id);
    }

    // ---------------------------------------------------------------- правила

    private static void checkStatus(AssetTypeView type, String status) {
        boolean known = type.statuses().stream().anyMatch(s -> s.code().equals(status));
        if (!known) {
            throw ApiException.validation("status", "invalid_option");
        }
    }

    private static Map<String, Object> validAttributes(AssetTypeView type, Map<String, Object> input) {
        AttributeValidator.Result result = AttributeValidator.validate(type.fields(), input);
        if (!result.isValid()) {
            Map<String, String> errors = new LinkedHashMap<>();
            result.errors().forEach((key, code) -> errors.put("attributes." + key, code));
            throw ApiException.validation(errors);
        }
        return new LinkedHashMap<>(result.values());
    }

    private void checkParent(UUID parentId, AssetTypeView childType, UUID selfId) {
        AssetView parent = find(parentId).orElseThrow(() -> ApiException.validation("parentId", "not_found"));
        if (parent.archived()) {
            throw ApiException.validation("parentId", "archived");
        }
        AssetTypeView parentType = types.get(parent.typeId());
        if (!parentType.allowedChildTypes().contains(childType.code())) {
            throw ApiException.validation("parentId", "child_type_not_allowed");
        }
        if (selfId != null) {
            Boolean cycle = db.sql("""
                            with recursive ancestors(id, parent_id) as (
                                select id, parent_id from asset where id = :parentId
                                union all
                                select a.id, a.parent_id from asset a join ancestors an on a.id = an.parent_id
                            )
                            select exists(select 1 from ancestors where id = :selfId)
                            """)
                    .param("parentId", parentId)
                    .param("selfId", selfId)
                    .query(Boolean.class)
                    .single();
            if (Boolean.TRUE.equals(cycle)) {
                throw ApiException.validation("parentId", "cycle");
            }
        }
    }

    /** Що змінилося: {"status": ["planned", "drilling"], "attributes.actualDepth": [null, 120.5]}. */
    private static Map<String, Object> changes(AssetView before, AssetView after) {
        Map<String, Object> changes = new LinkedHashMap<>();
        compare(changes, "parentId", before.parentId(), after.parentId());
        compare(changes, "code", before.code(), after.code());
        compare(changes, "name", before.name(), after.name());
        compare(changes, "status", before.status(), after.status());
        Set<String> keys = new LinkedHashSet<>(before.attributes().keySet());
        keys.addAll(after.attributes().keySet());
        for (String key : keys) {
            compare(changes, "attributes." + key, before.attributes().get(key), after.attributes().get(key));
        }
        return changes;
    }

    private static void compare(Map<String, Object> changes, String key, Object before, Object after) {
        if (!sameValue(before, after)) {
            changes.put(key, Arrays.asList(before, after));
        }
    }

    static boolean sameValue(Object a, Object b) {
        if (a instanceof Number && b instanceof Number) {
            return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString())) == 0;
        }
        return Objects.equals(a, b);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private AssetView map(ResultSet rs, int rowNum) throws SQLException {
        return new AssetView(
                rs.getObject("id", UUID.class),
                rs.getObject("asset_type_id", UUID.class),
                rs.getString("type_code"),
                rs.getObject("parent_id", UUID.class),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("status"),
                json.readValue(rs.getString("attributes"), ATTRIBUTES),
                rs.getInt("version"),
                rs.getObject("archived_at") != null,
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }
}
