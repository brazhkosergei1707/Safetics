package com.safetics.platform;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.safetics.assets.TemplateService;
import com.safetics.core.auth.AccountService;
import com.safetics.core.tenancy.TenantContext;
import com.safetics.core.web.ApiException;
import com.safetics.platform.PlatformDtos.CompanyAdminView;
import com.safetics.platform.PlatformDtos.CreateCompanyRequest;
import com.safetics.platform.PlatformDtos.CreateCompanyResponse;
import com.safetics.platform.PlatformDtos.PlatformCompanyView;

import tools.jackson.databind.json.JsonMapper;

/**
 * Дії адміна платформи: компанії й перші адміни. Бачить він лише компанії та їхні членства
 * (політики RLS з app_is_platform_admin()), даних компаній — ні. Кожна дія пишеться в
 * platform_audit_log.
 *
 * <p>Компанія створюється в контексті нової компанії: RLS дозволяє вставити company з
 * id = app_company_id(), а далі членство адміна, типи обладнання й посади з шаблону — як для
 * звичайних даних компанії. Транзакція починається вже всередині цього контексту, бо компанія
 * передається в базу при отриманні з'єднання.
 */
@Service
public class PlatformService {

    private final JdbcClient db;
    private final JsonMapper json;
    private final AccountService accounts;
    private final TemplateService templates;
    private final TransactionTemplate transactions;

    public PlatformService(JdbcClient db, JsonMapper json, AccountService accounts, TemplateService templates,
                           TransactionTemplate transactions) {
        this.db = db;
        this.json = json;
        this.accounts = accounts;
        this.templates = templates;
        this.transactions = transactions;
    }

    private record Row(UUID id, String name, String legalCode, String status, String templateKey,
                       OffsetDateTime createdAt, int members) {
    }

    public List<PlatformCompanyView> list() {
        List<Row> rows = db.sql("""
                        select c.id, c.name, c.legal_code, c.status, c.template_key, c.created_at,
                               (select count(*) from membership m where m.company_id = c.id and m.status = 'active') as members
                        from company c
                        order by c.created_at desc
                        """)
                .query(PlatformService::row).list();
        Map<UUID, List<CompanyAdminView>> admins = new LinkedHashMap<>();
        db.sql("""
                        select m.company_id, u.id, u.full_name, u.phone
                        from membership m
                                 join role r on r.id = m.role_id
                                 join app_user u on u.id = m.user_id
                        where r.code = 'admin' and r.company_id is null and m.status = 'active'
                        order by u.full_name
                        """)
                .query((rs, n) -> {
                    admins.computeIfAbsent(rs.getObject("company_id", UUID.class), k -> new ArrayList<>())
                            .add(new CompanyAdminView(rs.getObject("id", UUID.class), rs.getString("full_name"),
                                    rs.getString("phone")));
                    return null;
                })
                .list();
        return rows.stream().map(r -> view(r, admins.getOrDefault(r.id(), List.of()))).toList();
    }

    public PlatformCompanyView get(UUID id) {
        return list().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow(ApiException::notFound);
    }

    public CreateCompanyResponse create(CreateCompanyRequest request) {
        templates.load(request.templateKey());
        UUID platformAdmin = currentUserId();
        UUID companyId = UUID.randomUUID();

        Boolean newAccount = TenantContext.callAs(companyId, platformAdmin, () -> transactions.execute(status -> {
            db.sql("insert into company (id, name, legal_code, template_key) values (?, ?, ?, ?)")
                    .params(companyId, request.name().trim(), blank(request.legalCode()), request.templateKey())
                    .update();
            AccountService.Granted admin = accounts.grant(request.adminPhone(), request.adminFullName(),
                    request.adminPassword(), "admin");
            templates.install(request.templateKey());
            record(companyId, "create_company", Map.of("name", request.name().trim(), "template", request.templateKey(),
                    "adminUserId", admin.userId(), "newAdminAccount", admin.newAccount()));
            return admin.newAccount();
        }));
        return new CreateCompanyResponse(get(companyId), Boolean.TRUE.equals(newAccount));
    }

    /** active / suspended. Учасники призупиненої компанії не можуть увійти в неї. */
    public PlatformCompanyView setStatus(UUID id, String status) {
        PlatformCompanyView company = get(id);
        if (!company.status().equals(status)) {
            transactions.executeWithoutResult(tx -> {
                db.sql("update company set status = ? where id = ?").params(status, id).update();
                record(id, "suspended".equals(status) ? "suspend_company" : "activate_company", Map.of("name", company.name()));
                if ("suspended".equals(status)) {
                    // Нові токени для цієї компанії не видаються
                    db.sql("update refresh_token set revoked_at = now() where company_id = ? and revoked_at is null")
                            .param(id).update();
                }
            });
        }
        return get(id);
    }

    private void record(UUID companyId, String action, Object details) {
        db.sql("insert into platform_audit_log (user_id, company_id, action, details) values (?, ?, ?, cast(? as jsonb))")
                .params(currentUserId(), companyId, action, json.writeValueAsString(details)).update();
    }

    private static UUID currentUserId() {
        return TenantContext.current().map(TenantContext.Tenant::userId)
                .orElseThrow(() -> new IllegalStateException("No user in context"));
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Row row(ResultSet rs, int n) throws SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("legal_code"),
                rs.getString("status"), rs.getString("template_key"), rs.getObject("created_at", OffsetDateTime.class),
                rs.getInt("members"));
    }

    private static PlatformCompanyView view(Row r, List<CompanyAdminView> admins) {
        return new PlatformCompanyView(r.id(), r.name(), r.legalCode(), r.status(), r.templateKey(), r.createdAt(),
                admins, r.members());
    }
}
