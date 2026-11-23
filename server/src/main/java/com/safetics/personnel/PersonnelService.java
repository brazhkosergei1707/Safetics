package com.safetics.personnel;

import static com.safetics.customers.CustomerService.blankToNull;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.safetics.core.audit.AuditService;
import com.safetics.core.auth.AccountService;
import com.safetics.core.tenancy.TenantContext;
import com.safetics.core.web.ApiException;
import com.safetics.personnel.PersonnelDtos.PersonnelLoginRequest;
import com.safetics.personnel.PersonnelDtos.PersonnelLoginResponse;
import com.safetics.personnel.PersonnelDtos.PersonnelRequest;
import com.safetics.personnel.PersonnelDtos.PersonnelView;
import com.safetics.personnel.PersonnelDtos.PositionView;

/**
 * Виконавці компанії: штатні, субпідрядники, наймані. Вхід у систему необов'язковий; видається за
 * правилом «не більше прав, ніж маєш сам». Ролі порталу замовника виконавцям не видаються.
 */
@Service
public class PersonnelService {

    static final Set<String> EMPLOYMENT_TYPES = Set.of("staff", "subcontractor", "hired");

    private static final String WORKER_ROLE = "worker";

    private static final String SELECT = """
            select p.id, p.full_name, p.phone, p.employment_type, p.organization, p.position_id, p.user_id,
                   case when m.status = 'active' then r.code end as login_role, p.notes, p.version, p.archived_at
            from personnel p
                     left join membership m on m.company_id = p.company_id and m.user_id = p.user_id
                     left join role r on r.id = m.role_id
            """;

    public record Filter(String query, UUID positionId, boolean includeArchived) {
    }

    private final JdbcClient db;
    private final PositionService positions;
    private final AccountService accounts;
    private final AuditService audit;

    public PersonnelService(JdbcClient db, PositionService positions, AccountService accounts, AuditService audit) {
        this.db = db;
        this.positions = positions;
        this.accounts = accounts;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- читання

    public List<PersonnelView> list(Filter filter) {
        StringBuilder where = new StringBuilder(" where 1 = 1");
        List<Object> params = new ArrayList<>();
        if (!filter.includeArchived()) {
            where.append(" and p.archived_at is null");
        }
        if (filter.positionId() != null) {
            where.append(" and p.position_id = ?");
            params.add(filter.positionId());
        }
        if (filter.query() != null && !filter.query().isBlank()) {
            String like = "%" + filter.query().trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            where.append(" and (p.full_name ilike ? or p.phone ilike ? or p.organization ilike ?)");
            params.add(like);
            params.add(like);
            params.add(like);
        }
        return db.sql(SELECT + where + " order by lower(p.full_name)").params(params).query(PersonnelService::map).list();
    }

    public PersonnelView get(UUID id) {
        return find(id).orElseThrow(ApiException::notFound);
    }

    public Optional<PersonnelView> find(UUID id) {
        return db.sql(SELECT + " where p.id = ?").param(id).query(PersonnelService::map).optional();
    }

    // ---------------------------------------------------------------- зміни

    public PersonnelView create(PersonnelRequest request) {
        String employmentType = employmentType(request.employmentType());
        checkPosition(request.positionId(), null);
        UUID id = db.sql("""
                        insert into personnel (company_id, full_name, phone, employment_type, organization, position_id, notes)
                        values (?, ?, ?, ?, ?, ?, ?)
                        returning id
                        """)
                .params(TenantContext.requireCompanyId(), request.fullName().trim(), blankToNull(request.phone()),
                        employmentType, blankToNull(request.organization()), request.positionId(),
                        blankToNull(request.notes()))
                .query(UUID.class).single();
        audit.record("personnel", id, "create", Map.of("fullName", request.fullName().trim(), "employmentType", employmentType));
        return get(id);
    }

    public PersonnelView update(UUID id, PersonnelRequest request) {
        PersonnelView existing = get(id);
        if (request.version() == null) {
            throw ApiException.validation("version", "required");
        }
        if (request.version() != existing.version()) {
            throw ApiException.conflict("stale_version");
        }
        String employmentType = employmentType(request.employmentType());
        checkPosition(request.positionId(), existing.positionId());
        int updated = db.sql("""
                        update personnel
                        set full_name = ?, phone = ?, employment_type = ?, organization = ?, position_id = ?, notes = ?,
                            version = version + 1, updated_at = now()
                        where id = ? and version = ?
                        """)
                .params(request.fullName().trim(), blankToNull(request.phone()), employmentType,
                        blankToNull(request.organization()), request.positionId(), blankToNull(request.notes()),
                        id, request.version())
                .update();
        if (updated == 0) {
            throw ApiException.conflict("stale_version");
        }
        PersonnelView saved = get(id);
        Map<String, Object> changes = new LinkedHashMap<>();
        compare(changes, "fullName", existing.fullName(), saved.fullName());
        compare(changes, "phone", existing.phone(), saved.phone());
        compare(changes, "employmentType", existing.employmentType(), saved.employmentType());
        compare(changes, "organization", existing.organization(), saved.organization());
        compare(changes, "positionId", existing.positionId(), saved.positionId());
        compare(changes, "notes", existing.notes(), saved.notes());
        if (!changes.isEmpty()) {
            audit.record("personnel", id, "update", changes);
        }
        return saved;
    }

    /** В архів — лише без поточних і майбутніх призначень; вхід у систему закривається. */
    @Transactional
    public PersonnelView archive(UUID id) {
        PersonnelView existing = get(id);
        if (existing.archived()) {
            return existing;
        }
        Long active = db.sql("""
                        select count(*) from personnel_assignment
                        where personnel_id = ? and (end_date is null or end_date >= current_date)
                        """)
                .param(id).query(Long.class).single();
        if (active > 0) {
            throw ApiException.conflict("has_active_assignment");
        }
        if (existing.userId() != null) {
            revokeLogin(id);
        }
        db.sql("update personnel set archived_at = now(), version = version + 1, updated_at = now() where id = ?")
                .param(id).update();
        audit.record("personnel", id, "archive", null);
        return get(id);
    }

    public PersonnelView restore(UUID id) {
        PersonnelView existing = get(id);
        if (!existing.archived()) {
            return existing;
        }
        db.sql("update personnel set archived_at = null, version = version + 1, updated_at = now() where id = ?")
                .param(id).update();
        audit.record("personnel", id, "restore", null);
        return get(id);
    }

    // ---------------------------------------------------------------- вхід у систему

    @Transactional
    public PersonnelLoginResponse grantLogin(UUID id, PersonnelLoginRequest request) {
        PersonnelView person = get(id);
        if (person.archived()) {
            throw ApiException.conflict("personnel_archived");
        }
        if (person.loginRole() != null) {
            throw ApiException.conflict("already_has_access");
        }
        // Вхід виконавця — завжди роль «Виконавець»; інші ролі дає адмін на екрані «Користувачі»
        accounts.requireNotMoreThanOwn(WORKER_ROLE);
        if (person.phone() == null) {
            throw ApiException.validation("phone", "required");
        }
        AccountService.Granted granted = accounts.grant(person.phone(), person.fullName(), request.password(),
                WORKER_ROLE, true);
        Optional<String> actualRole = accounts.activeRoleOf(granted.userId());
        if (actualRole.isPresent() && AccountService.PORTAL_ROLES.contains(actualRole.get())) {
            throw ApiException.conflict("already_member");
        }
        db.sql("update personnel set user_id = ?, version = version + 1, updated_at = now() where id = ?")
                .params(granted.userId(), id).update();
        // Доступ до проєктів, на які людину вже призначено (поточні й майбутні призначення)
        db.sql("""
                        insert into project_access (company_id, project_id, user_id, granted_by)
                        select a.company_id, a.project_id, ?, ?
                        from personnel_assignment a
                        where a.personnel_id = ? and (a.end_date is null or a.end_date >= current_date)
                        on conflict do nothing
                        """)
                .params(granted.userId(), currentUserId(), id).update();
        audit.record("personnel", id, "grant_login",
                Map.of("role", actualRole.orElse(WORKER_ROLE), "userId", granted.userId(), "newAccount", granted.newAccount()));
        return new PersonnelLoginResponse(get(id), granted.newAccount());
    }

    @Transactional
    public PersonnelView revokeLogin(UUID id) {
        PersonnelView person = get(id);
        if (person.userId() == null) {
            return person;
        }
        if (person.userId().equals(currentUserId())) {
            throw ApiException.conflict("cannot_revoke_self");
        }
        if (person.loginRole() != null) {
            accounts.requireNotMoreThanOwn(person.loginRole());
            try {
                accounts.disable(person.userId());
            } catch (DataIntegrityViolationException e) {
                if (String.valueOf(e.getMessage()).contains("at least one active admin")) {
                    throw ApiException.conflict("last_admin");
                }
                throw e;
            }
        }
        db.sql("update personnel set user_id = null, version = version + 1, updated_at = now() where id = ?")
                .param(id).update();
        audit.record("personnel", id, "revoke_login", Map.of("userId", person.userId()));
        return get(id);
    }

    // ---------------------------------------------------------------- правила

    private void checkPosition(UUID positionId, UUID currentPositionId) {
        if (positionId == null || positionId.equals(currentPositionId)) {
            return;
        }
        PositionView position = positions.find(positionId)
                .orElseThrow(() -> ApiException.validation("positionId", "not_found"));
        if (position.archived()) {
            throw ApiException.validation("positionId", "archived");
        }
    }

    static String employmentType(String raw) {
        String value = raw == null || raw.isBlank() ? "staff" : raw;
        if (!EMPLOYMENT_TYPES.contains(value)) {
            throw ApiException.validation("employmentType", "invalid_option");
        }
        return value;
    }

    private static UUID currentUserId() {
        return TenantContext.current().map(TenantContext.Tenant::userId).orElse(null);
    }

    private static void compare(Map<String, Object> changes, String key, Object before, Object after) {
        if (!Objects.equals(before, after)) {
            changes.put(key, Arrays.asList(before, after));
        }
    }

    private static PersonnelView map(ResultSet rs, int rowNum) throws SQLException {
        return new PersonnelView(
                rs.getObject("id", UUID.class),
                rs.getString("full_name"),
                rs.getString("phone"),
                rs.getString("employment_type"),
                rs.getString("organization"),
                rs.getObject("position_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("login_role"),
                rs.getString("notes"),
                rs.getInt("version"),
                rs.getObject("archived_at") != null);
    }
}
