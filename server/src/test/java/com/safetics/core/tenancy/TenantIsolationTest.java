package com.safetics.core.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.safetics.AbstractIntegrationTest;

/**
 * Головна гарантія мультитенантності: компанія A не бачить і не змінює дані компанії B.
 * Перевірка на рівні бази, без HTTP. Те саме через API перевіряє AuthApiTest.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantIsolationTest extends AbstractIntegrationTest {

    /** Застосунок: з'єднання через TenantAwareDataSource під safetics_app. */
    @Autowired
    JdbcClient app;

    UUID companyA;
    UUID companyB;
    UUID userA;
    UUID userB;

    @BeforeAll
    void insertFixtures() {
        JdbcClient admin = admin();

        companyA = insertReturningId(admin, "insert into company (name) values ('Drilling A') returning id");
        companyB = insertReturningId(admin, "insert into company (name) values ('Drilling B') returning id");
        userA = insertReturningId(admin,
                "insert into app_user (phone, full_name) values ('+380500000001', 'User A') returning id");
        userB = insertReturningId(admin,
                "insert into app_user (phone, full_name) values ('+380500000002', 'User B') returning id");

        UUID ownerRole = admin.sql("select id from role where code = 'owner' and company_id is null")
                .query(UUID.class).single();

        admin.sql("insert into membership (company_id, user_id, role_id) values (?, ?, ?)")
                .params(companyA, userA, ownerRole).update();
        admin.sql("insert into membership (company_id, user_id, role_id) values (?, ?, ?)")
                .params(companyB, userB, ownerRole).update();

        admin.sql("insert into role (company_id, code, name) values (?, 'storekeeper', 'Комірник')")
                .param(companyA).update();

        for (UUID company : List.of(companyA, companyA, companyB)) {
            admin.sql("insert into audit_log (company_id, entity, entity_id, action) values (?, 'company', '1', 'create')")
                    .param(company).update();
        }
    }

    @Test
    void withoutTenantNothingIsVisible() {
        assertThat(count("select count(*) from company")).isZero();
        assertThat(count("select count(*) from membership")).isZero();
        assertThat(count("select count(*) from audit_log")).isZero();
    }

    @Test
    void tenantSeesOnlyOwnRows() {
        TenantContext.runAs(companyA, userA, () -> {
            assertThat(app.sql("select id from company").query(UUID.class).list()).containsExactly(companyA);
            assertThat(count("select count(*) from membership")).isEqualTo(1);
            assertThat(count("select count(*) from audit_log")).isEqualTo(2);
        });
        TenantContext.runAs(companyB, userB, () -> {
            assertThat(app.sql("select id from company").query(UUID.class).list()).containsExactly(companyB);
            assertThat(count("select count(*) from audit_log")).isEqualTo(1);
        });
    }

    @Test
    void cannotInsertIntoAnotherCompany() {
        TenantContext.runAs(companyA, userA, () ->
                assertThatThrownBy(() -> app.sql(
                                "insert into audit_log (company_id, entity, entity_id, action) values (?, 'x', '1', 'create')")
                        .param(companyB).update())
                        .isInstanceOf(DataAccessException.class));
    }

    @Test
    void cannotUpdateAnotherCompany() {
        TenantContext.runAs(companyA, userA, () -> {
            int updated = app.sql("update company set name = 'hacked' where id = ?").param(companyB).update();
            assertThat(updated).isZero();
        });
    }

    @Test
    void systemRolesAreSharedCompanyRolesArePrivate() {
        List<String> rolesOfB = TenantContext.callAs(companyB, userB,
                () -> app.sql("select code from role").query(String.class).list());
        assertThat(rolesOfB).contains("owner", "admin", "manager", "worker", "client_rep")
                .doesNotContain("storekeeper");

        List<String> rolesOfA = TenantContext.callAs(companyA, userA,
                () -> app.sql("select code from role").query(String.class).list());
        assertThat(rolesOfA).contains("storekeeper");
    }

    @Test
    void auditLogIsAppendOnly() {
        TenantContext.runAs(companyA, userA, () ->
                assertThatThrownBy(() -> app.sql("update audit_log set action = 'tampered'").update())
                        .isInstanceOf(DataAccessException.class));
    }

    @Test
    void tenantIsClearedWhenConnectionReturnsToPool() {
        TenantContext.runAs(companyA, userA, () -> assertThat(count("select count(*) from company")).isEqualTo(1));
        assertThat(count("select count(*) from company")).isZero();
    }

    @Test
    void appRoleCannotBypassRls() {
        Map<String, Object> role = app.sql("select rolsuper, rolbypassrls from pg_roles where rolname = current_user")
                .query().singleRow();
        assertThat(role).containsEntry("rolsuper", false).containsEntry("rolbypassrls", false);
    }

    private long count(String sql) {
        return app.sql(sql).query(Long.class).single();
    }

    private static UUID insertReturningId(JdbcClient client, String sql) {
        return client.sql(sql).query(UUID.class).single();
    }
}
