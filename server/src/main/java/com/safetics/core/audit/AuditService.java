package com.safetics.core.audit;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.safetics.core.tenancy.TenantContext;
import com.safetics.core.tenancy.TenantContext.Tenant;

import tools.jackson.databind.json.JsonMapper;

/**
 * Запис у журнал змін поточної компанії. Компанія й автор беруться з {@link TenantContext}.
 * Журнал лише на додавання: роль застосунку не має прав на UPDATE і DELETE.
 */
@Service
public class AuditService {

    private final JdbcClient db;
    private final JsonMapper json;

    public AuditService(JdbcClient db, JsonMapper json) {
        this.db = db;
        this.json = json;
    }

    /**
     * @param entity   тип запису: asset, asset_type…
     * @param entityId ідентифікатор запису
     * @param action   create, update, archive, restore…
     * @param changes  що змінилося (будь-який об'єкт, зберігається як JSON) або null
     */
    public void record(String entity, Object entityId, String action, Object changes) {
        Tenant tenant = TenantContext.current()
                .filter(t -> t.companyId() != null)
                .orElseThrow(() -> new IllegalStateException("Audit record outside of a company context"));
        db.sql("""
                        insert into audit_log (company_id, user_id, entity, entity_id, action, changes)
                        values (?, ?, ?, ?, ?, cast(? as jsonb))
                        """)
                .params(tenant.companyId(), tenant.userId(), entity, String.valueOf(entityId), action,
                        changes == null ? null : json.writeValueAsString(changes))
                .update();
    }
}
