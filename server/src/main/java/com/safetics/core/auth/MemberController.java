package com.safetics.core.auth;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Учасники поточної компанії (обліковий запис + роль). Потрібні, щоб обрати, кому дати доступ до
 * проєкту. Повне керування людьми і ролями — пізніше (staff.manage).
 */
@RestController
@RequestMapping("/api/v1/members")
@Tag(name = "members")
public class MemberController {

    /**
     * portal — роль порталу замовника (бачить проєкти свого замовника без окремого доступу);
     * allProjects — роль має project.access_all.
     */
    public record MemberView(
            @NotNull UUID userId,
            @NotNull String fullName,
            @NotNull String phone,
            @NotNull String role,
            @NotNull String status,
            @NotNull boolean portal,
            @NotNull boolean allProjects) {
    }

    private final JdbcClient db;

    public MemberController(JdbcClient db) {
        this.db = db;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('project.manage') or hasAuthority('staff.manage')")
    public List<MemberView> list() {
        return db.sql("""
                        select u.id, u.full_name, u.phone, r.code as role, m.status,
                               exists (select 1 from role_permission rp
                                       where rp.role_id = m.role_id and rp.permission_code = 'project.access_all') as all_projects
                        from membership m
                                 join app_user u on u.id = m.user_id
                                 join role r on r.id = m.role_id
                        where m.company_id = app_company_id()
                        order by u.full_name
                        """)
                .query((rs, n) -> new MemberView(
                        rs.getObject("id", UUID.class),
                        rs.getString("full_name"),
                        rs.getString("phone"),
                        rs.getString("role"),
                        rs.getString("status"),
                        AccountService.PORTAL_ROLES.contains(rs.getString("role")),
                        rs.getBoolean("all_projects")))
                .list();
    }
}
