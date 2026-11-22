package com.safetics.core.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.safetics.AbstractIntegrationTest;

/**
 * Компанія не може лишитися без адміна: базу не обійти навіть помилкою в коді.
 * Перевірка йде під роллю застосунку, як у реальних запитах.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LastAdminProtectionTest extends AbstractIntegrationTest {

    @Autowired
    JdbcClient app;

    UUID company;
    UUID firstAdmin;
    UUID secondUser;
    UUID adminRole;
    UUID managerRole;

    @BeforeAll
    void insertFixtures() {
        JdbcClient admin = admin();
        company = admin.sql("insert into company (name) values ('Last admin Co') returning id").query(UUID.class).single();
        firstAdmin = admin.sql("insert into app_user (phone, full_name) values ('+380673000001', 'First') returning id")
                .query(UUID.class).single();
        secondUser = admin.sql("insert into app_user (phone, full_name) values ('+380673000002', 'Second') returning id")
                .query(UUID.class).single();
        adminRole = admin.sql("select id from role where code = 'admin' and company_id is null").query(UUID.class).single();
        managerRole = admin.sql("select id from role where code = 'manager' and company_id is null").query(UUID.class).single();
        admin.sql("insert into membership (company_id, user_id, role_id) values (?, ?, ?)")
                .params(company, firstAdmin, adminRole).update();
        admin.sql("insert into membership (company_id, user_id, role_id) values (?, ?, ?)")
                .params(company, secondUser, managerRole).update();
    }

    @Test
    void lastAdminCannotBeDemotedDisabledOrRemoved() {
        TenantContext.runAs(company, firstAdmin, () -> {
            assertThatThrownBy(() -> setRole(firstAdmin, managerRole)).isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> app.sql("update membership set status = 'disabled' where user_id = ? and company_id = ?")
                    .params(firstAdmin, company).update()).isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> app.sql("delete from membership where user_id = ? and company_id = ?")
                    .params(firstAdmin, company).update()).isInstanceOf(DataAccessException.class);
        });
    }

    @Test
    void adminCanBeDemotedOnceAnotherAdminExists() {
        TenantContext.runAs(company, firstAdmin, () -> {
            assertThat(setRole(secondUser, adminRole)).isEqualTo(1);
            assertThat(setRole(firstAdmin, managerRole)).isEqualTo(1);
            // Повертаємо як було, щоб тести не залежали від порядку
            assertThat(setRole(firstAdmin, adminRole)).isEqualTo(1);
            assertThat(setRole(secondUser, managerRole)).isEqualTo(1);
        });
    }

    private int setRole(UUID user, UUID role) {
        return app.sql("update membership set role_id = ? where user_id = ? and company_id = ?")
                .params(role, user, company).update();
    }
}
