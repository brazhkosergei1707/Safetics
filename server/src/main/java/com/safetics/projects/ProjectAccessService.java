package com.safetics.projects;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.safetics.core.audit.AuditService;
import com.safetics.core.auth.AccountService;
import com.safetics.core.tenancy.TenantContext;
import com.safetics.core.web.ApiException;
import com.safetics.projects.ProjectDtos.ProjectAccessView;

/**
 * Доступ до проєкту для людей без project.access_all. Видає той, хто сам бачить проєкт і має
 * project.manage. Ролям порталу доступ не видається: вони бачать проєкти свого замовника.
 */
@Service
public class ProjectAccessService {

    private record Member(String role, String status) {
    }

    private final JdbcClient db;
    private final ProjectService projects;
    private final AuditService audit;

    public ProjectAccessService(JdbcClient db, ProjectService projects, AuditService audit) {
        this.db = db;
        this.projects = projects;
        this.audit = audit;
    }

    public List<ProjectAccessView> list(UUID projectId) {
        projects.get(projectId);
        return db.sql("""
                        select u.id, u.full_name, u.phone, r.code as role, g.full_name as granted_by_name, pa.created_at
                        from project_access pa
                                 join app_user u on u.id = pa.user_id
                                 join membership m on m.company_id = pa.company_id and m.user_id = pa.user_id
                                 join role r on r.id = m.role_id
                                 left join app_user g on g.id = pa.granted_by
                        where pa.project_id = ?
                        order by u.full_name
                        """)
                .param(projectId)
                .query((rs, n) -> new ProjectAccessView(
                        rs.getObject("id", UUID.class),
                        rs.getString("full_name"),
                        rs.getString("phone"),
                        rs.getString("role"),
                        rs.getString("granted_by_name"),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .list();
    }

    public List<ProjectAccessView> grant(UUID projectId, UUID userId) {
        projects.get(projectId);
        Member member = db.sql("""
                        select r.code, m.status
                        from membership m join role r on r.id = m.role_id
                        where m.company_id = app_company_id() and m.user_id = ?
                        """)
                .param(userId)
                .query((rs, n) -> new Member(rs.getString("code"), rs.getString("status")))
                .optional()
                .orElseThrow(() -> ApiException.validation("userId", "not_member"));
        if (!"active".equals(member.status())) {
            throw ApiException.validation("userId", "not_active");
        }
        if (AccountService.PORTAL_ROLES.contains(member.role())) {
            throw ApiException.validation("userId", "portal_role");
        }
        int added = db.sql("""
                        insert into project_access (company_id, project_id, user_id, granted_by)
                        values (?, ?, ?, ?)
                        on conflict do nothing
                        """)
                .params(TenantContext.requireCompanyId(), projectId, userId,
                        TenantContext.current().map(TenantContext.Tenant::userId).orElse(null))
                .update();
        if (added > 0) {
            audit.record("project", projectId, "grant_access", Map.of("userId", userId));
        }
        return list(projectId);
    }

    public List<ProjectAccessView> revoke(UUID projectId, UUID userId) {
        projects.get(projectId);
        int removed = db.sql("delete from project_access where project_id = ? and user_id = ?")
                .params(projectId, userId).update();
        if (removed > 0) {
            audit.record("project", projectId, "revoke_access", Map.of("userId", userId));
        }
        return list(projectId);
    }
}
