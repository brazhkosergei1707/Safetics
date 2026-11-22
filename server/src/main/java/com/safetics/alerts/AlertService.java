package com.safetics.alerts;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.safetics.core.tenancy.TenantContext;

/**
 * Алерти користувачам (критична несправність з чек-листа…). Поки видно у вебі (дзвіночок у шапці);
 * push — разом із сервісом сповіщень. Кожен бачить лише свої.
 */
@Service
public class AlertService {

    /** kind: critical_checklist — критичний пункт чек-листа не пройдено. */
    public record AlertView(
            @NotNull UUID id,
            @NotNull String kind,
            @NotNull String title,
            UUID projectId,
            UUID taskId,
            Long taskNumber,
            @NotNull OffsetDateTime createdAt,
            @NotNull boolean read) {
    }

    public record AlertsView(
            @NotNull long unread,
            @NotNull List<AlertView> items) {
    }

    private final JdbcClient db;

    public AlertService(JdbcClient db) {
        this.db = db;
    }

    /**
     * Алерт менеджерам проєкту: активні члени з project.manage, які бачать проєкт (project.access_all
     * або доступ до проєкту). Від імені системи. Повертає кількість отримувачів.
     */
    @Transactional
    public int notifyManagers(UUID projectId, String kind, String title, UUID taskId) {
        String previous = db.sql("select coalesce(current_setting('app.system', true), '')").query(String.class).single();
        db.sql("select set_config('app.system', 'on', true)").query(String.class).single();
        try {
            return db.sql("""
                            insert into alert (company_id, user_id, project_id, kind, title, task_id)
                            select distinct m.company_id, m.user_id, ?, ?, ?, ?
                            from membership m
                                     join role_permission rp on rp.role_id = m.role_id and rp.permission_code = 'project.manage'
                            where m.company_id = ? and m.status = 'active'
                              and (exists (select 1 from role_permission r2
                                           where r2.role_id = m.role_id and r2.permission_code = 'project.access_all')
                                   or exists (select 1 from project_access pa where pa.project_id = ? and pa.user_id = m.user_id))
                            """)
                    .params(projectId, kind, title.length() > 300 ? title.substring(0, 300) : title, taskId,
                            TenantContext.requireCompanyId(), projectId)
                    .update();
        } finally {
            db.sql("select set_config('app.system', ?, true)").param(previous).query(String.class).single();
        }
    }

    public AlertsView mine() {
        long unread = db.sql("select count(*) from alert where user_id = app_user_id() and read_at is null")
                .query(Long.class).single();
        List<AlertView> items = db.sql("""
                        select a.id, a.kind, a.title, a.project_id, a.task_id, t.number, a.created_at, a.read_at
                        from alert a left join task t on t.id = a.task_id
                        where a.user_id = app_user_id()
                        order by a.read_at is null desc, a.created_at desc
                        limit 50
                        """)
                .query((rs, n) -> new AlertView(rs.getObject("id", UUID.class), rs.getString("kind"), rs.getString("title"),
                        rs.getObject("project_id", UUID.class), rs.getObject("task_id", UUID.class),
                        rs.getObject("number") == null ? null : rs.getLong("number"),
                        rs.getObject("created_at", OffsetDateTime.class), rs.getObject("read_at") != null))
                .list();
        return new AlertsView(unread, items);
    }

    public void markRead(UUID id) {
        db.sql("update alert set read_at = now() where id = ? and user_id = app_user_id() and read_at is null").param(id).update();
    }

    public void markAllRead() {
        db.sql("update alert set read_at = now() where user_id = app_user_id() and read_at is null").update();
    }
}
