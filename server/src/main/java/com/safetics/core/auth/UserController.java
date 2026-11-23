package com.safetics.core.auth;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.safetics.core.audit.AuditService;
import com.safetics.core.tenancy.TenantContext;
import com.safetics.core.web.ApiException;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Користувачі компанії (облікові записи з членством): додати, змінити роль, вимкнути, відновити.
 * staff.manage. Правило «не більше прав, ніж маєш сам» — і для ролі, яку забирають, і для нової.
 * Ролі порталу замовника керуються через контактних осіб, тут вони лише показуються.
 */
@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "users")
@PreAuthorize("hasAuthority('staff.manage')")
public class UserController {

    /** portal — роль порталу замовника (змінюється через контактну особу). */
    public record CompanyUserView(
            @NotNull UUID userId,
            @NotNull String fullName,
            @NotNull String phone,
            @NotNull String role,
            @NotNull @Schema(allowableValues = {"active", "invited", "disabled"}) String status,
            @NotNull boolean portal) {
    }

    /** password — лише для нового облікового запису. */
    public record AddUserRequest(@NotBlank String phone, @NotBlank String fullName, @NotBlank String role, String password) {
    }

    public record ChangeRoleRequest(@NotBlank String role) {
    }

    private final JdbcClient db;
    private final AccountService accounts;
    private final AuditService audit;

    public UserController(JdbcClient db, AccountService accounts, AuditService audit) {
        this.db = db;
        this.accounts = accounts;
        this.audit = audit;
    }

    @GetMapping
    public List<CompanyUserView> list() {
        // Лише офісні ролі: виконавці — на екрані «Виконавці», портал — у контактах замовника
        return db.sql(SELECT + " and r.code in ('admin', 'director', 'head_manager', 'manager') order by m.status, u.full_name")
                .query(UserController::map).list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public CompanyUserView add(@Valid @RequestBody AddUserRequest request) {
        requireStaffRole(request.role());
        accounts.requireNotMoreThanOwn(request.role());
        AccountService.Granted granted = accounts.grant(request.phone(), request.fullName(), request.password(), request.role());
        audit.record("user", granted.userId(), "add", Map.of("role", request.role(), "newAccount", granted.newAccount()));
        return get(granted.userId());
    }

    @PutMapping("/{userId}/role")
    @Transactional
    public CompanyUserView changeRole(@PathVariable("userId") UUID userId, @Valid @RequestBody ChangeRoleRequest request) {
        CompanyUserView user = editable(userId);
        requireStaffRole(request.role());
        accounts.requireNotMoreThanOwn(user.role());
        accounts.requireNotMoreThanOwn(request.role());
        withLastAdminCheck(() -> db.sql("""
                        update membership set role_id = (select id from role where code = ? and company_id is null)
                        where company_id = ? and user_id = ?
                        """)
                .params(request.role(), TenantContext.requireCompanyId(), userId).update());
        audit.record("user", userId, "change_role", Map.of("before", user.role(), "after", request.role()));
        return get(userId);
    }

    @PostMapping("/{userId}/disable")
    @Transactional
    public CompanyUserView disable(@PathVariable("userId") UUID userId) {
        CompanyUserView user = editable(userId);
        accounts.requireNotMoreThanOwn(user.role());
        withLastAdminCheck(() -> {
            accounts.disable(userId);
            return 1;
        });
        audit.record("user", userId, "disable", null);
        return get(userId);
    }

    @PostMapping("/{userId}/enable")
    @Transactional
    public CompanyUserView enable(@PathVariable("userId") UUID userId) {
        CompanyUserView user = editable(userId);
        accounts.requireNotMoreThanOwn(user.role());
        db.sql("update membership set status = 'active' where company_id = ? and user_id = ?")
                .params(TenantContext.requireCompanyId(), userId).update();
        audit.record("user", userId, "enable", null);
        return get(userId);
    }

    // ---------------------------------------------------------------- допоміжне

    private static final String SELECT = """
            select u.id, u.full_name, u.phone, r.code as role, m.status
            from membership m
                     join app_user u on u.id = m.user_id
                     join role r on r.id = m.role_id
            where m.company_id = app_company_id()
            """;

    private CompanyUserView get(UUID userId) {
        return db.sql(SELECT + " and m.user_id = ?").param(userId).query(UserController::map).optional()
                .orElseThrow(ApiException::notFound);
    }

    /** Себе не змінюють (щоб не втратити доступ), учасників порталу — через контактних осіб. */
    private CompanyUserView editable(UUID userId) {
        CompanyUserView user = get(userId);
        if (userId.equals(TenantContext.current().map(TenantContext.Tenant::userId).orElse(null))) {
            throw ApiException.conflict("cannot_change_self");
        }
        if (user.portal()) {
            throw ApiException.conflict("portal_member");
        }
        return user;
    }

    private static void requireStaffRole(String role) {
        if (!AccountService.OFFICE_ROLES.contains(role)) {
            throw ApiException.validation("role", "invalid_option");
        }
    }

    private static void withLastAdminCheck(java.util.function.Supplier<Integer> action) {
        try {
            action.get();
        } catch (DataIntegrityViolationException e) {
            if (String.valueOf(e.getMessage()).contains("at least one active admin")) {
                throw ApiException.conflict("last_admin");
            }
            throw e;
        }
    }

    private static CompanyUserView map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        String role = rs.getString("role");
        return new CompanyUserView(rs.getObject("id", UUID.class), rs.getString("full_name"), rs.getString("phone"), role,
                rs.getString("status"), AccountService.PORTAL_ROLES.contains(role));
    }
}
