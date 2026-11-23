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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.safetics.assets.AssetDtos.AssetTypeOrderRequest;
import com.safetics.assets.AssetDtos.AssetTypeRequest;
import com.safetics.assets.AssetDtos.AssetTypeView;
import com.safetics.assets.AssetDtos.ChecklistItem;
import com.safetics.assets.AssetDtos.LibraryTypeView;
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

    private static final TypeReference<List<ChecklistItem>> CHECKLIST = new TypeReference<>() {
    };
    private static final String SELECT = """
            select id, code, name, category, fields, statuses, allowed_child_types, checklist, template_key, archived_at
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
        return db.sql(SELECT + (includeArchived ? "" : " where archived_at is null") + " order by sort_order, code")
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
                        insert into asset_type (company_id, code, name, category, fields, statuses, allowed_child_types,
                                                checklist, template_key)
                        values (?, ?, cast(? as jsonb), ?, cast(? as jsonb), cast(? as jsonb), cast(? as jsonb), cast(? as jsonb), ?)
                        returning id
                        """)
                .params(TenantContext.requireCompanyId(), request.code(), json.writeValueAsString(request.name()), category(request),
                        json.writeValueAsString(request.fields()), json.writeValueAsString(request.statuses()),
                        json.writeValueAsString(childTypes(request)),
                        json.writeValueAsString(request.checklist() == null ? List.of() : request.checklist()), templateKey)
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
                            allowed_child_types = cast(? as jsonb), checklist = cast(? as jsonb), updated_at = now()
                        where id = ?
                        """)
                .params(json.writeValueAsString(request.name()), category(request), json.writeValueAsString(request.fields()),
                        json.writeValueAsString(request.statuses()), json.writeValueAsString(childTypes(request)),
                        json.writeValueAsString(request.checklist() == null ? existing.checklist() : request.checklist()), id)
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

    /**
     * Видалити тип: лише коли немає обладнання в роботі (409 type_has_assets, fields.assets — кількість).
     * Є архівне обладнання — тип ховається (архів), історія лишається; немає жодного — видаляється повністю.
     * Код типу прибирається з «можна вкладати» інших типів.
     */
    @Transactional
    public void delete(UUID id) {
        AssetTypeView existing = get(id);
        long active = db.sql("select count(*) from asset where asset_type_id = ? and archived_at is null")
                .param(id).query(Long.class).single();
        if (active > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "type_has_assets", Map.of("assets", Long.toString(active)));
        }
        long archived = db.sql("select count(*) from asset where asset_type_id = ?").param(id).query(Long.class).single();
        db.sql("""
                        update asset_type set allowed_child_types = allowed_child_types - ?, updated_at = now()
                        where allowed_child_types @> jsonb_build_array(?::text)
                        """)
                .params(existing.code(), existing.code()).update();
        if (archived > 0) {
            db.sql("update asset_type set archived_at = coalesce(archived_at, now()), updated_at = now() where id = ?")
                    .param(id).update();
        } else {
            db.sql("delete from asset_type where id = ?").param(id).update();
        }
        audit.record("asset_type", id, "delete", Map.of("code", existing.code(), "hidden", archived > 0));
    }

    /** Порядок вкладок: передаються всі активні типи компанії в новому порядку. */
    @Transactional
    public List<AssetTypeView> reorder(AssetTypeOrderRequest request) {
        List<UUID> active = db.sql("select id from asset_type where archived_at is null").query(UUID.class).list();
        if (request.ids().size() != active.size() || !new HashSet<>(request.ids()).equals(new HashSet<>(active))) {
            throw ApiException.validation("ids", "not_all_types");
        }
        for (int i = 0; i < request.ids().size(); i++) {
            db.sql("update asset_type set sort_order = ?, updated_at = now() where id = ?").params(i + 1, request.ids().get(i))
                    .update();
        }
        audit.record("asset_type", TenantContext.requireCompanyId(), "reorder", Map.of("count", request.ids().size()));
        return list(false);
    }

    // ---------------------------------------------------------------- бібліотека

    /**
     * Бібліотека: активні типи інших компаній, однакові код і назва — один рядок (найсвіжіший варіант).
     * Читається в режимі app.library (політика library_read), лише в межах цієї транзакції.
     */
    @Transactional(readOnly = true)
    public List<LibraryTypeView> library() {
        Set<String> own = new HashSet<>(db.sql("select code from asset_type where archived_at is null").query(String.class).list());
        UUID company = TenantContext.requireCompanyId();
        enterLibrary();
        try {
            return db.sql("""
                            with t as (
                                select id, company_id, code, name, category, fields, checklist, updated_at,
                                       lower(coalesce(name ->> 'en', name ->> 'uk', code)) as name_key
                                from asset_type
                                where archived_at is null and company_id <> ?
                            ), g as (
                                select (array_agg(id order by updated_at desc))[1] as id, count(distinct company_id) as companies
                                from t group by code, name_key
                            )
                            select t.id, t.code, t.name, t.category, jsonb_array_length(t.fields) as fields_count,
                                   jsonb_array_length(t.checklist) as checklist_count, g.companies
                            from g join t on t.id = g.id
                            order by g.companies desc, t.code
                            """)
                    .param(company)
                    .query((rs, n) -> new LibraryTypeView(rs.getObject("id", UUID.class), rs.getString("code"),
                            json.readValue(rs.getString("name"), LABELS), rs.getString("category"), rs.getInt("fields_count"),
                            rs.getInt("checklist_count"), rs.getInt("companies"), own.contains(rs.getString("code"))))
                    .list();
        } finally {
            leaveLibrary();
        }
    }

    /**
     * Додати тип з бібліотеки: копія полів, статусів, чек-листа й категорії; «можна вкладати» — лише коди,
     * що вже є в компанії. Код уже є — 409 code_exists; є серед видалених (архів) — тип відновлюється з новим описом.
     */
    @Transactional
    public AssetTypeView install(UUID sourceId) {
        enterLibrary();
        AssetTypeView source;
        try {
            source = db.sql(SELECT + " where id = ? and archived_at is null and company_id <> ?")
                    .params(sourceId, TenantContext.requireCompanyId()).query(this::map).optional()
                    .orElseThrow(ApiException::notFound);
        } finally {
            leaveLibrary();
        }
        Set<String> own = new HashSet<>(db.sql("select code from asset_type where archived_at is null").query(String.class).list());
        List<String> children = source.allowedChildTypes().stream().filter(own::contains).toList();
        AssetTypeRequest request = new AssetTypeRequest(source.code(), source.name(), source.category(), source.fields(),
                source.statuses(), children, source.checklist());
        Optional<AssetTypeView> existing = findByCode(source.code());
        UUID id;
        if (existing.isPresent() && !existing.get().archived()) {
            throw ApiException.conflict("code_exists");
        }
        if (existing.isPresent()) {
            update(existing.get().id(), request);
            id = existing.get().id();
            db.sql("""
                            update asset_type
                            set archived_at = null, source_type_id = ?,
                                sort_order = (select coalesce(max(sort_order), 0) + 1 from asset_type where archived_at is null)
                            where id = ?
                            """)
                    .params(sourceId, id).update();
        } else {
            id = create(request, null).id();
            db.sql("update asset_type set source_type_id = ? where id = ?").params(sourceId, id).update();
        }
        audit.record("asset_type", id, "install_library", Map.of("code", source.code(), "sourceId", sourceId));
        return get(id);
    }

    private void enterLibrary() {
        db.sql("select set_config('app.library', 'on', true)").query(String.class).single();
    }

    private void leaveLibrary() {
        db.sql("select set_config('app.library', '', true)").query(String.class).single();
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

        validateFields(request.fields(), errors);
        validateChecklist(request.checklist(), errors);

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

    /** Перевірка опису полів (для типів обладнання й типів послуг): ключі, типи, варіанти, межі. */
    public static void validateFields(List<FieldDefinition> fields, Map<String, String> errors) {
        validateFields(fields, errors, "fields");
    }

    /** @param path назва списку в помилках: fields[0].key, closingFields[1].type */
    public static void validateFields(List<FieldDefinition> fields, Map<String, String> errors, String path) {
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < fields.size(); i++) {
            FieldDefinition field = fields.get(i);
            String prefix = path + "[" + i + "].";
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
    }

    private static String category(AssetTypeRequest request) {
        return request.category() == null ? DEFAULT_CATEGORY : request.category();
    }

    /** Пункти чек-листа: ключ латиницею (унікальний), назва хоча б однією мовою. */
    static void validateChecklist(List<ChecklistItem> checklist, Map<String, String> errors) {
        if (checklist == null) {
            return;
        }
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < checklist.size(); i++) {
            ChecklistItem item = checklist.get(i);
            String prefix = "checklist[" + i + "].";
            if (item.key() == null || !item.key().matches("[a-z][a-z0-9_]{0,49}")) {
                errors.put(prefix + "key", "invalid_format");
            } else if (!keys.add(item.key())) {
                errors.put(prefix + "key", "duplicate");
            }
            if (item.label() == null || item.label().values().stream().allMatch(v -> v == null || v.isBlank())) {
                errors.put(prefix + "label", "required");
            }
        }
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
                json.readValue(rs.getString("checklist"), CHECKLIST),
                rs.getString("template_key"),
                rs.getObject("archived_at") != null);
    }
}
