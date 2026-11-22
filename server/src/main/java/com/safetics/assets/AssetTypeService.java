package com.safetics.assets;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.safetics.assets.AssetDtos.AssetTypeRequest;
import com.safetics.assets.AssetDtos.AssetTypeView;
import com.safetics.core.audit.AuditService;
import com.safetics.core.tenancy.TenantContext;
import com.safetics.core.web.ApiException;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Типи обладнання компанії (в коді — asset). Компанія фільтрується RLS, тому в запитах немає {@code where company_id}.
 * Зміна схеми полів не чіпає наявні об'єкти: нові правила діють при наступному збереженні.
 */
@Service
public class AssetTypeService {

    static final Pattern CODE = Pattern.compile("^[a-z][a-z0-9_]{1,49}$");
    static final Pattern FIELD_KEY = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]{0,49}$");
    static final Pattern STATUS_CODE = Pattern.compile("^[a-z][a-z0-9_]{0,49}$");
    static final List<String> CATEGORIES = List.of("primary", "auxiliary");
    static final String DEFAULT_CATEGORY = "auxiliary";

    private static final TypeReference<List<FieldDefinition>> FIELDS = new TypeReference<>() {
    };
    private static final TypeReference<List<StatusDefinition>> STATUSES = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, String>> LABELS = new TypeReference<>() {
    };

    private static final String SELECT = """
            select id, code, name, category, fields, statuses, allowed_child_types, template_key, archived_at
            from asset_type
            """;

    private final JdbcClient db;
    private final JsonMapper json;
    private final AuditService audit;

    public AssetTypeService(JdbcClient db, JsonMapper json, AuditService audit) {
        this.db = db;
        this.json = json;
        this.audit = audit;
    }

    public List<AssetTypeView> list(boolean includeArchived) {
        return db.sql(SELECT + (includeArchived ? "" : " where archived_at is null") + " order by code")
                .query(this::map)
                .list();
    }

    public AssetTypeView get(UUID id) {
        return find(id).orElseThrow(ApiException::notFound);
    }

    public Optional<AssetTypeView> find(UUID id) {
        return db.sql(SELECT + " where id = ?").param(id).query(this::map).optional();
    }

    public Optional<AssetTypeView> findByCode(String code) {
        return db.sql(SELECT + " where code = ?").param(code).query(this::map).optional();
    }

    public AssetTypeView create(AssetTypeRequest request, String templateKey) {
        validateDefinition(request);
        if (findByCode(request.code()).isPresent()) {
            throw ApiException.validation("code", "duplicate");
        }
        UUID id = db.sql("""
                        insert into asset_type (company_id, code, name, category, fields, statuses, allowed_child_types, template_key)
                        values (?, ?, cast(? as jsonb), ?, cast(? as jsonb), cast(? as jsonb), cast(? as jsonb), ?)
                        returning id
                        """)
                .params(TenantContext.requireCompanyId(), request.code(), json.writeValueAsString(request.name()), category(request),
                        json.writeValueAsString(request.fields()), json.writeValueAsString(request.statuses()),
                        json.writeValueAsString(childTypes(request)), templateKey)
                .query(UUID.class)
                .single();
        audit.record("asset_type", id, "create", Map.of("code", request.code()));
        return get(id);
    }

    public AssetTypeView update(UUID id, AssetTypeRequest request) {
        AssetTypeView existing = get(id);
        if (!existing.code().equals(request.code())) {
            throw ApiException.validation("code", "immutable");
        }
        validateDefinition(request);
        db.sql("""
                        update asset_type
                        set name = cast(? as jsonb), category = ?, fields = cast(? as jsonb), statuses = cast(? as jsonb),
                            allowed_child_types = cast(? as jsonb), updated_at = now()
                        where id = ?
                        """)
                .params(json.writeValueAsString(request.name()), category(request), json.writeValueAsString(request.fields()),
                        json.writeValueAsString(request.statuses()), json.writeValueAsString(childTypes(request)), id)
                .update();
        audit.record("asset_type", id, "update", Map.of("code", request.code()));
        return get(id);
    }

    /** Архів типу: нові записи цього типу не створюються, наявне обладнання лишається. */
    public AssetTypeView setArchived(UUID id, boolean archived) {
        AssetTypeView existing = get(id);
        if (existing.archived() == archived) {
            return existing;
        }
        db.sql("update asset_type set archived_at = " + (archived ? "now()" : "null") + ", updated_at = now() where id = ?")
                .param(id).update();
        audit.record("asset_type", id, archived ? "archive" : "restore", Map.of("code", existing.code()));
        return get(id);
    }

    /** Перевірка самої схеми типу: ключі полів, типи значень, варіанти enum, статуси. */
    static void validateDefinition(AssetTypeRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (!CODE.matcher(request.code()).matches()) {
            errors.put("code", "invalid_format");
        }
        if (request.category() != null && !CATEGORIES.contains(request.category())) {
            errors.put("category", "invalid_option");
        }
        if (request.name().isEmpty() || request.name().values().stream().allMatch(v -> v == null || v.isBlank())) {
            errors.put("name", "required");
        }

        Set<String> keys = new HashSet<>();
        for (int i = 0; i < request.fields().size(); i++) {
            FieldDefinition field = request.fields().get(i);
            String prefix = "fields[" + i + "].";
            if (field.key() == null || !FIELD_KEY.matcher(field.key()).matches()) {
                errors.put(prefix + "key", "invalid_format");
            } else if (!keys.add(field.key())) {
                errors.put(prefix + "key", "duplicate");
            }
            if (!FieldDefinition.TYPES.contains(field.type())) {
                errors.put(prefix + "type", "invalid_option");
            }
            if ("enum".equals(field.type()) && (field.options() == null || field.options().isEmpty())) {
                errors.put(prefix + "options", "required");
            }
            if (field.min() != null && field.max() != null && field.min().compareTo(field.max()) > 0) {
                errors.put(prefix + "max", "too_small");
            }
        }

        if (request.statuses().isEmpty()) {
            errors.put("statuses", "required");
        }
        Set<String> statusCodes = new HashSet<>();
        for (int i = 0; i < request.statuses().size(); i++) {
            String code = request.statuses().get(i).code();
            if (code == null || !STATUS_CODE.matcher(code).matches()) {
                errors.put("statuses[" + i + "].code", "invalid_format");
            } else if (!statusCodes.add(code)) {
                errors.put("statuses[" + i + "].code", "duplicate");
            }
        }

        for (String child : childTypes(request)) {
            if (!CODE.matcher(child).matches()) {
                errors.put("allowedChildTypes", "invalid_format");
            }
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
    }

    private static String category(AssetTypeRequest request) {
        return request.category() == null ? DEFAULT_CATEGORY : request.category();
    }

    private static List<String> childTypes(AssetTypeRequest request) {
        return request.allowedChildTypes() == null ? List.of() : request.allowedChildTypes();
    }

    private AssetTypeView map(ResultSet rs, int rowNum) throws SQLException {
        return new AssetTypeView(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                json.readValue(rs.getString("name"), LABELS),
                rs.getString("category"),
                json.readValue(rs.getString("fields"), FIELDS),
                json.readValue(rs.getString("statuses"), STATUSES),
                json.readValue(rs.getString("allowed_child_types"), STRINGS),
                rs.getString("template_key"),
                rs.getObject("archived_at") != null);
    }
}
