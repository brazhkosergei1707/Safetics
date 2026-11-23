package com.safetics.core.auth;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.safetics.core.tenancy.TenantContext;
import com.safetics.core.web.ApiException;

/**
 * Видача й відкликання входу в поточну компанію: обліковий запис (один на платформу, за телефоном)
 * + членство з роллю.
 *
 * <p>Якщо обліковий запис з цим телефоном уже є (людина працює в іншій компанії), додається лише
 * членство, пароль не змінюється. Новий обліковий запис потребує початкового пароля.
 */
@Service
public class AccountService {

    /** Ролі порталу замовника: видаються лише контактним особам замовника. */
    public static final Set<String> PORTAL_ROLES = Set.of("client_rep", "client_manager");

    /** Системні ролі працівників компанії (не портал). */
    public static final Set<String> STAFF_ROLES = Set.of("admin", "director", "head_manager", "manager", "worker");

    /** Офісні ролі — екран «Користувачі» (адмін). Виконавців додають менеджери на екрані «Виконавці». */
    public static final Set<String> OFFICE_ROLES = Set.of("admin", "director", "head_manager", "manager");

    static final int MIN_PASSWORD_LENGTH = 8;

    private final JdbcClient db;
    private final PasswordEncoder passwordEncoder;

    public AccountService(JdbcClient db, PasswordEncoder passwordEncoder) {
        this.db = db;
        this.passwordEncoder = passwordEncoder;
    }

    /** newAccount — обліковий запис створено зараз (інакше він уже існував і пароль не змінено). */
    public record Granted(UUID userId, boolean newAccount) {
    }

    private record Membership(UUID id, String roleCode, String status) {
    }

    private record UserRow(UUID id, String status) {
    }

    @Transactional
    public Granted grant(String rawPhone, String fullName, String password, String roleCode) {
        return grant(rawPhone, fullName, password, roleCode, false);
    }

    /**
     * Дає людині вхід у поточну компанію з системною роллю {@code roleCode}.
     *
     * @param linkActiveMember якщо людина вже активний учасник компанії — нічого не змінювати і
     *                         повернути її (щоб прив'язати виконавця до наявного входу); інакше 409
     * @throws ApiException 409 already_member, 409 account_blocked, 400 password — для нового запису
     */
    @Transactional
    public Granted grant(String rawPhone, String fullName, String password, String roleCode, boolean linkActiveMember) {
        UUID companyId = TenantContext.requireCompanyId();
        String phone = AuthService.normalizePhone(rawPhone);
        if (phone.length() < 8) {
            throw ApiException.validation("phone", "invalid_format");
        }
        UUID roleId = db.sql("select id from role where code = ? and company_id is null")
                .param(roleCode).query(UUID.class).optional()
                .orElseThrow(() -> ApiException.validation("role", "invalid_option"));

        Optional<UserRow> existing = db.sql("select id, status from app_user where phone = ?")
                .param(phone)
                .query((rs, n) -> new UserRow(rs.getObject("id", UUID.class), rs.getString("status")))
                .optional();

        boolean newAccount = existing.isEmpty();
        UUID userId;
        if (newAccount) {
            if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
                throw ApiException.validation("password", password == null || password.isEmpty() ? "required" : "too_short");
            }
            userId = db.sql("insert into app_user (phone, full_name, password_hash) values (?, ?, ?) returning id")
                    .params(phone, fullName.trim(), passwordEncoder.encode(password))
                    .query(UUID.class).single();
        } else {
            if (!"active".equals(existing.get().status())) {
                throw ApiException.conflict("account_blocked");
            }
            userId = existing.get().id();
        }

        Optional<Membership> membership = db.sql("""
                        select m.id, r.code, m.status
                        from membership m join role r on r.id = m.role_id
                        where m.company_id = ? and m.user_id = ?
                        """)
                .params(companyId, userId)
                .query((rs, n) -> new Membership(rs.getObject("id", UUID.class), rs.getString("code"),
                        rs.getString("status")))
                .optional();

        if (membership.isEmpty()) {
            db.sql("insert into membership (company_id, user_id, role_id) values (?, ?, ?)")
                    .params(companyId, userId, roleId).update();
        } else if (linkActiveMember && "active".equals(membership.get().status())) {
            return new Granted(userId, false);
        } else if ("disabled".equals(membership.get().status())
                && PORTAL_ROLES.contains(membership.get().roleCode()) == PORTAL_ROLES.contains(roleCode)) {
            // Колишній вхід (портал → портал, працівник → працівник) відновлюється з новою роллю
            db.sql("update membership set role_id = ?, status = 'active' where id = ?")
                    .params(roleId, membership.get().id()).update();
        } else {
            throw ApiException.conflict("already_member");
        }
        return new Granted(userId, newAccount);
    }

    /** Вимикає членство в поточній компанії і відкликає токени оновлення для неї. */
    @Transactional
    public void disable(UUID userId) {
        UUID companyId = TenantContext.requireCompanyId();
        db.sql("update membership set status = 'disabled' where company_id = ? and user_id = ?")
                .params(companyId, userId).update();
        db.sql("update refresh_token set revoked_at = now() where user_id = ? and company_id = ? and revoked_at is null")
                .params(userId, companyId).update();
    }

    /**
     * Правило «не більше прав, ніж маєш сам»: усі дозволи ролі мають бути в поточного користувача
     * (з його токена). Інакше 403 role_exceeds_own. Виняток: адміну не потрібен personnel.manage.
     */
    public void requireNotMoreThanOwn(String roleCode) {
        Set<String> own = currentPermissions();
        List<String> rolePermissions = db.sql("""
                        select rp.permission_code
                        from role r join role_permission rp on rp.role_id = r.id
                        where r.code = ? and r.company_id is null
                        """)
                .param(roleCode).query(String.class).list();
        // Адмін (staff.manage) видає будь-яку офісну роль: керування виконавцями — обов'язок менеджерів, а не більші права
        if (own.contains("staff.manage")) {
            rolePermissions = rolePermissions.stream().filter(code -> !"personnel.manage".equals(code)).toList();
        }
        if (!own.containsAll(rolePermissions)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "role_exceeds_own", Map.of());
        }
    }

    /** Код ролі користувача в поточній компанії, якщо членство активне. */
    public Optional<String> activeRoleOf(UUID userId) {
        return db.sql("""
                        select r.code from membership m join role r on r.id = m.role_id
                        where m.company_id = ? and m.user_id = ? and m.status = 'active'
                        """)
                .params(TenantContext.requireCompanyId(), userId).query(String.class).optional();
    }

    private static Set<String> currentPermissions() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Set<String> permissions = new HashSet<>();
        if (authentication != null) {
            for (GrantedAuthority authority : authentication.getAuthorities()) {
                permissions.add(authority.getAuthority());
            }
        }
        return permissions;
    }
}
