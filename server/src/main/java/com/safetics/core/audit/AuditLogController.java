package com.safetics.core.audit;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Журнал змін поточної компанії. Перший ендпоінт з перевіркою дозволу: потрібен {@code audit.view}.
 * Фільтр за компанією в SQL не потрібен: RLS сам віддає лише рядки компанії з токена.
 */
@RestController
@RequestMapping("/api/v1/audit-log")
public class AuditLogController {

    public record AuditEntry(long id, UUID userId, String entity, String entityId, String action,
                             OffsetDateTime createdAt) {
    }

    private final JdbcClient db;

    public AuditLogController(JdbcClient db) {
        this.db = db;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('audit.view')")
    public List<AuditEntry> latest(@RequestParam(name = "limit", defaultValue = "50") int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        return db.sql("""
                        select id, user_id, entity, entity_id, action, created_at
                        from audit_log
                        order by created_at desc, id desc
                        limit ?
                        """)
                .param(safeLimit)
                .query((rs, n) -> new AuditEntry(rs.getLong("id"), rs.getObject("user_id", UUID.class),
                        rs.getString("entity"), rs.getString("entity_id"), rs.getString("action"),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .list();
    }
}
