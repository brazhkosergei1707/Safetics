package com.safetics.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.safetics.AbstractIntegrationTest;
import com.safetics.TestApi;
import com.safetics.TestApi.Response;
import com.safetics.core.tenancy.TenantContext;

/**
 * Адмін платформи: створює компанії з першим адміном і шаблоном, призупиняє їх; бачить компанії,
 * але не дані компаній. Звичайний користувач API платформи не має.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlatformApiTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "platform-test-password";

    @Value("${local.server.port}")
    int port;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JdbcClient app;

    UUID platformUser;
    TestApi platform;
    TestApi ordinary;

    @BeforeAll
    void setUp() {
        String hash = passwordEncoder.encode(PASSWORD);
        platformUser = admin().sql("""
                        insert into app_user (phone, full_name, password_hash, is_platform_admin)
                        values ('+380679100001', 'Platform', ?, true) returning id
                        """)
                .param(hash).query(UUID.class).single();
        UUID company = addCompany("Platform Ordinary Co");
        addMember(company, "+380679100002", hash, "admin");
        addMember(company, "+380679100003", hash, "worker");
        platform = TestApi.loggedIn(port, "+380679100001", PASSWORD);
        ordinary = TestApi.loggedIn(port, "+380679100002", PASSWORD);
    }

    @Test
    void sessionKnowsPlatformAdmin() {
        Response me = platform.get("/api/v1/auth/me");
        assertThat(me.at("platformAdmin")).isEqualTo(true);
        assertThat(me.at("company")).isNull();
        assertThat(ordinary.get("/api/v1/auth/me").at("platformAdmin")).isEqualTo(false);
    }

    @Test
    void createsCompanyForEveryTemplate() {
        List<Object> templates = platform.get("/api/v1/platform/templates").list();
        assertThat(templates).extracting(t -> (Object) ((Map<?, ?>) t).get("key"))
                .contains("exploration-drilling", "water-well-drilling", "furniture", "tire-service", "construction");

        int i = 10;
        for (Object t : templates) {
            String key = (String) ((Map<?, ?>) t).get("key");
            String phone = "+3806791000" + i++;
            Response created = platform.post("/api/v1/platform/companies",
                    company("Template " + key, key, "Admin " + key, phone, "first-admin-pass"));
            assertThat(created.status()).as(key + ": " + created.body()).isEqualTo(201);
            assertThat(created.at("newAdminAccount")).isEqualTo(true);
            assertThat(created.str("company.templateKey")).isEqualTo(key);
            assertThat((List<?>) created.at("company.admins")).hasSize(1);

            TestApi firstAdmin = TestApi.loggedIn(port, phone, "first-admin-pass");
            assertThat(firstAdmin.get("/api/v1/auth/me").str("company.name")).isEqualTo("Template " + key);
            assertThat(firstAdmin.get("/api/v1/asset-types").list()).as(key + " asset types").isNotEmpty();
            assertThat(firstAdmin.get("/api/v1/positions").list()).as(key + " positions").isNotEmpty();
        }
    }

    @Test
    void existingAccountBecomesAdminWithoutPassword() {
        Response created = platform.post("/api/v1/platform/companies",
                company("Second job Co", "furniture", "Worker Elsewhere", "+380679100003", null));
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.at("newAdminAccount")).isEqualTo(false);
        Response login = new TestApi(port).post("/api/v1/auth/login", Map.of("phone", "+380679100003", "password", PASSWORD));
        assertThat((List<?>) login.at("companies")).hasSize(2);

        assertThat(platform.post("/api/v1/platform/companies",
                company("No password Co", "furniture", "New Person", "+380679100099", null)).field("password"))
                .isEqualTo("required");
        assertThat(platform.post("/api/v1/platform/companies",
                company("Bad template Co", "spaceships", "X", "+380679100098", "first-admin-pass")).field("templateKey"))
                .isEqualTo("unknown_template");
    }

    @Test
    void suspendedCompanyCannotBeEntered() {
        Response created = platform.post("/api/v1/platform/companies",
                company("Suspended Co", "tire-service", "Suspended Admin", "+380679100050", "first-admin-pass"));
        String id = created.str("company.id");

        assertThat(platform.post("/api/v1/platform/companies/" + id + "/suspend", Map.of()).str("status")).isEqualTo("suspended");
        Response login = new TestApi(port).post("/api/v1/auth/login",
                Map.of("phone", "+380679100050", "password", "first-admin-pass"));
        assertThat(login.at("company")).isNull();

        assertThat(platform.post("/api/v1/platform/companies/" + id + "/activate", Map.of()).str("status")).isEqualTo("active");
        assertThat(TestApi.loggedIn(port, "+380679100050", "first-admin-pass").get("/api/v1/auth/me").str("company.name"))
                .isEqualTo("Suspended Co");
    }

    @Test
    void onlyPlatformAdminAndNoCompanyData() {
        assertThat(ordinary.get("/api/v1/platform/companies").status()).isEqualTo(403);
        assertThat(ordinary.post("/api/v1/platform/companies",
                company("Hack Co", "furniture", "X", "+380679100097", "first-admin-pass")).status()).isEqualTo(403);
        assertThat(platform.get("/api/v1/platform/companies").list()).isNotEmpty();

        UUID ordinaryUser = admin().sql("select id from app_user where phone = '+380679100002'").query(UUID.class).single();
        Long seenByOrdinary = TenantContext.callAs(null, ordinaryUser,
                () -> app.sql("select count(*) from company").query(Long.class).single());
        assertThat(seenByOrdinary).as("лише свої компанії").isEqualTo(1);

        Long companies = TenantContext.callAs(null, platformUser,
                () -> app.sql("select count(*) from company").query(Long.class).single());
        Long assetTypes = TenantContext.callAs(null, platformUser,
                () -> app.sql("select count(*) from asset_type").query(Long.class).single());
        assertThat(companies).isGreaterThan(1);
        assertThat(assetTypes).as("дані компаній адміну платформи не видно").isZero();
    }

    private static Map<String, Object> company(String name, String template, String adminName, String phone, String password) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("templateKey", template);
        body.put("adminFullName", adminName);
        body.put("adminPhone", phone);
        body.put("adminPassword", password);
        return body;
    }
}
