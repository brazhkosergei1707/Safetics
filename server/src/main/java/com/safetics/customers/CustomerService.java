package com.safetics.customers;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.safetics.customers.CustomerDtos.CustomerRequest;
import com.safetics.customers.CustomerDtos.CustomerView;
import com.safetics.core.audit.AuditService;
import com.safetics.core.tenancy.TenantContext;
import com.safetics.core.web.ApiException;

/** Замовники компанії: створення, зміна (з версією), архів. Назва унікальна в межах компанії. */
@Service
public class CustomerService {

    private static final String SELECT = """
            select id, name, legal_code, country, phone, email, notes, version, archived_at
            from customer
            """;

    private final JdbcClient db;
    private final AuditService audit;

    public CustomerService(JdbcClient db, AuditService audit) {
        this.db = db;
        this.audit = audit;
    }

    public List<CustomerView> list(String query, boolean includeArchived) {
        StringBuilder where = new StringBuilder(" where 1 = 1");
        List<Object> params = new ArrayList<>();
        if (!includeArchived) {
            where.append(" and archived_at is null");
        }
        if (query != null && !query.isBlank()) {
            where.append(" and (name ilike ? or legal_code ilike ?)");
            String like = like(query);
            params.add(like);
            params.add(like);
        }
        return db.sql(SELECT + where + " order by lower(name)").params(params).query(CustomerService::map).list();
    }

    public CustomerView get(UUID id) {
        return find(id).orElseThrow(ApiException::notFound);
    }

    public Optional<CustomerView> find(UUID id) {
        return db.sql(SELECT + " where id = ?").param(id).query(CustomerService::map).optional();
    }

    public CustomerView create(CustomerRequest request) {
        String country = country(request.country());
        UUID id = db.sql("""
                        insert into customer (company_id, name, legal_code, country, phone, email, notes)
                        values (?, ?, ?, ?, ?, ?, ?)
                        returning id
                        """)
                .params(TenantContext.requireCompanyId(), request.name().trim(), blankToNull(request.legalCode()),
                        country, blankToNull(request.phone()), blankToNull(request.email()), blankToNull(request.notes()))
                .query(UUID.class)
                .single();
        audit.record("customer", id, "create", Map.of("name", request.name().trim()));
        return get(id);
    }

    public CustomerView update(UUID id, CustomerRequest request) {
        CustomerView existing = get(id);
        requireVersion(request.version(), existing.version());
        String country = country(request.country());
        int updated = db.sql("""
                        update customer
                        set name = ?, legal_code = ?, country = ?, phone = ?, email = ?, notes = ?,
                            version = version + 1, updated_at = now()
                        where id = ? and version = ?
                        """)
                .params(request.name().trim(), blankToNull(request.legalCode()), country, blankToNull(request.phone()),
                        blankToNull(request.email()), blankToNull(request.notes()), id, request.version())
                .update();
        if (updated == 0) {
            throw ApiException.conflict("stale_version");
        }
        CustomerView saved = get(id);
        Map<String, Object> changes = new LinkedHashMap<>();
        compare(changes, "name", existing.name(), saved.name());
        compare(changes, "legalCode", existing.legalCode(), saved.legalCode());
        compare(changes, "country", existing.country(), saved.country());
        compare(changes, "phone", existing.phone(), saved.phone());
        compare(changes, "email", existing.email(), saved.email());
        compare(changes, "notes", existing.notes(), saved.notes());
        if (!changes.isEmpty()) {
            audit.record("customer", id, "update", changes);
        }
        return saved;
    }

    /** Замовника з активними проєктами архівувати не можна. */
    public CustomerView archive(UUID id) {
        CustomerView existing = get(id);
        if (existing.archived()) {
            return existing;
        }
        Long activeProjects = db.sql("select count(*) from project where customer_id = ? and status = 'active'")
                .param(id).query(Long.class).single();
        if (activeProjects > 0) {
            throw ApiException.conflict("has_active_projects");
        }
        db.sql("update customer set archived_at = now(), version = version + 1, updated_at = now() where id = ?")
                .param(id).update();
        audit.record("customer", id, "archive", null);
        return get(id);
    }

    public CustomerView restore(UUID id) {
        CustomerView existing = get(id);
        if (!existing.archived()) {
            return existing;
        }
        db.sql("update customer set archived_at = null, version = version + 1, updated_at = now() where id = ?")
                .param(id).update();
        audit.record("customer", id, "restore", null);
        return get(id);
    }

    // ---------------------------------------------------------------- допоміжне

    static void requireVersion(Integer requested, int actual) {
        if (requested == null) {
            throw ApiException.validation("version", "required");
        }
        if (requested != actual) {
            throw ApiException.conflict("stale_version");
        }
    }

    /** Код країни ISO 3166-1 alpha-2 у верхньому регістрі або null. */
    public static String country(String raw) {
        String value = blankToNull(raw);
        if (value == null) {
            return null;
        }
        value = value.toUpperCase();
        if (!value.matches("[A-Z]{2}")) {
            throw ApiException.validation("country", "invalid_format");
        }
        return value;
    }

    static void compare(Map<String, Object> changes, String key, Object before, Object after) {
        if (!Objects.equals(before, after)) {
            changes.put(key, Arrays.asList(before, after));
        }
    }

    static String like(String query) {
        return "%" + query.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    public static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static CustomerView map(ResultSet rs, int rowNum) throws SQLException {
        return new CustomerView(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("legal_code"),
                rs.getString("country"),
                rs.getString("phone"),
                rs.getString("email"),
                rs.getString("notes"),
                rs.getInt("version"),
                rs.getObject("archived_at") != null);
    }
}
