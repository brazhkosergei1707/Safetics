package com.safetics.customers;

import static com.safetics.customers.CustomerService.blankToNull;
import static com.safetics.customers.CustomerService.compare;
import static com.safetics.customers.CustomerService.requireVersion;

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

import com.safetics.customers.CustomerDtos.ContactRequest;
import com.safetics.customers.CustomerDtos.ContactView;
import com.safetics.customers.CustomerDtos.CustomerView;
import com.safetics.customers.CustomerDtos.PortalAccessRequest;
import com.safetics.customers.CustomerDtos.PortalAccessResponse;
import com.safetics.core.audit.AuditService;
import com.safetics.core.auth.AccountService;
import com.safetics.core.tenancy.TenantContext;
import com.safetics.core.web.ApiException;

/**
 * Контактні особи замовника і їхній вхід у портал. Контактна особа з входом бачить усі проєкти
 * свого замовника (правило в RLS проєктів) і нічого іншого в компанії.
 */
@Service
public class ContactService {

    private static final String SELECT = """
            select cc.id, cc.customer_id, cc.full_name, cc.job_title, cc.phone, cc.email, cc.version, cc.archived_at,
                   case when m.status = 'active' then r.code end as portal_role
            from customer_contact cc
                     left join membership m on m.company_id = cc.company_id and m.user_id = cc.user_id
                     left join role r on r.id = m.role_id
            """;

    private final JdbcClient db;
    private final CustomerService customers;
    private final AccountService accounts;
    private final AuditService audit;

    public ContactService(JdbcClient db, CustomerService customers, AccountService accounts, AuditService audit) {
        this.db = db;
        this.customers = customers;
        this.accounts = accounts;
        this.audit = audit;
    }

    public List<ContactView> list(UUID customerId, boolean includeArchived) {
        customers.get(customerId);
        return db.sql(SELECT + " where cc.customer_id = ?" + (includeArchived ? "" : " and cc.archived_at is null")
                        + " order by lower(cc.full_name)")
                .param(customerId)
                .query(ContactService::map)
                .list();
    }

    public ContactView get(UUID id) {
        return find(id).orElseThrow(ApiException::notFound);
    }

    public Optional<ContactView> find(UUID id) {
        return db.sql(SELECT + " where cc.id = ?").param(id).query(ContactService::map).optional();
    }

    public ContactView create(UUID customerId, ContactRequest request) {
        CustomerView customer = customers.get(customerId);
        if (customer.archived()) {
            throw ApiException.conflict("customer_archived");
        }
        UUID id = db.sql("""
                        insert into customer_contact (company_id, customer_id, full_name, job_title, phone, email)
                        values (?, ?, ?, ?, ?, ?)
                        returning id
                        """)
                .params(TenantContext.requireCompanyId(), customerId, request.fullName().trim(),
                        blankToNull(request.jobTitle()), blankToNull(request.phone()), blankToNull(request.email()))
                .query(UUID.class)
                .single();
        audit.record("customer_contact", id, "create", Map.of("customerId", customerId, "fullName", request.fullName().trim()));
        return get(id);
    }

    /** Зміна телефону не змінює вхід: людина входить телефоном свого облікового запису. */
    public ContactView update(UUID id, ContactRequest request) {
        ContactView existing = get(id);
        requireVersion(request.version(), existing.version());
        int updated = db.sql("""
                        update customer_contact
                        set full_name = ?, job_title = ?, phone = ?, email = ?, version = version + 1, updated_at = now()
                        where id = ? and version = ?
                        """)
                .params(request.fullName().trim(), blankToNull(request.jobTitle()), blankToNull(request.phone()),
                        blankToNull(request.email()), id, request.version())
                .update();
        if (updated == 0) {
            throw ApiException.conflict("stale_version");
        }
        ContactView saved = get(id);
        Map<String, Object> changes = new LinkedHashMap<>();
        compare(changes, "fullName", existing.fullName(), saved.fullName());
        compare(changes, "jobTitle", existing.jobTitle(), saved.jobTitle());
        compare(changes, "phone", existing.phone(), saved.phone());
        compare(changes, "email", existing.email(), saved.email());
        if (!changes.isEmpty()) {
            audit.record("customer_contact", id, "update", changes);
        }
        return saved;
    }

    /** Архів контактної особи закриває і її вхід у портал. */
    @Transactional
    public ContactView archive(UUID id) {
        ContactView existing = get(id);
        if (existing.archived()) {
            return existing;
        }
        revokeLogin(id);
        db.sql("update customer_contact set archived_at = now(), version = version + 1, updated_at = now() where id = ?")
                .param(id).update();
        audit.record("customer_contact", id, "archive", null);
        return get(id);
    }

    public ContactView restore(UUID id) {
        ContactView existing = get(id);
        if (!existing.archived()) {
            return existing;
        }
        if (customers.get(existing.customerId()).archived()) {
            throw ApiException.conflict("customer_archived");
        }
        db.sql("update customer_contact set archived_at = null, version = version + 1, updated_at = now() where id = ?")
                .param(id).update();
        audit.record("customer_contact", id, "restore", null);
        return get(id);
    }

    // ---------------------------------------------------------------- портал

    @Transactional
    public PortalAccessResponse grantPortalAccess(UUID id, PortalAccessRequest request) {
        ContactView contact = get(id);
        if (contact.archived()) {
            throw ApiException.conflict("contact_archived");
        }
        if (!AccountService.PORTAL_ROLES.contains(request.role())) {
            throw ApiException.validation("role", "invalid_option");
        }
        if (linkedUser(id).isPresent()) {
            throw ApiException.conflict("already_has_access");
        }
        if (contact.phone() == null) {
            throw ApiException.validation("phone", "required");
        }
        AccountService.Granted granted = accounts.grant(contact.phone(), contact.fullName(), request.password(), request.role());
        db.sql("update customer_contact set user_id = ?, version = version + 1, updated_at = now() where id = ?")
                .params(granted.userId(), id).update();
        audit.record("customer_contact", id, "grant_portal_access",
                Map.of("role", request.role(), "userId", granted.userId(), "newAccount", granted.newAccount()));
        return new PortalAccessResponse(get(id), granted.newAccount());
    }

    @Transactional
    public ContactView revokePortalAccess(UUID id) {
        get(id);
        if (revokeLogin(id)) {
            audit.record("customer_contact", id, "revoke_portal_access", null);
        }
        return get(id);
    }

    private boolean revokeLogin(UUID contactId) {
        Optional<UUID> userId = linkedUser(contactId);
        if (userId.isEmpty()) {
            return false;
        }
        db.sql("update customer_contact set user_id = null, version = version + 1, updated_at = now() where id = ?")
                .param(contactId).update();
        accounts.disable(userId.get());
        return true;
    }

    private Optional<UUID> linkedUser(UUID contactId) {
        return db.sql("select user_id from customer_contact where id = ? and user_id is not null")
                .param(contactId).query(UUID.class).optional();
    }

    private static ContactView map(ResultSet rs, int rowNum) throws SQLException {
        return new ContactView(
                rs.getObject("id", UUID.class),
                rs.getObject("customer_id", UUID.class),
                rs.getString("full_name"),
                rs.getString("job_title"),
                rs.getString("phone"),
                rs.getString("email"),
                rs.getString("portal_role"),
                rs.getInt("version"),
                rs.getObject("archived_at") != null);
    }
}
