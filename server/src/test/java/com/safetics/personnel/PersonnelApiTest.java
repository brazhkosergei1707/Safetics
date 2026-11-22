package com.safetics.personnel;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.safetics.AbstractIntegrationTest;
import com.safetics.TestApi;
import com.safetics.TestApi.Response;

/**
 * Посади з шаблону і виконавці: створення, версії, вхід у систему за правилом «не більше прав,
 * ніж маєш сам», прив'язка до наявного входу, відкликання.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PersonnelApiTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "personnel-test-password";

    @Value("${local.server.port}")
    int port;

    @Autowired
    PasswordEncoder passwordEncoder;

    TestApi admin;
    TestApi manager;
    Map<String, String> positionIds = new HashMap<>();

    @BeforeAll
    void setUp() {
        UUID company = addCompany("Personnel Co");
        String hash = passwordEncoder.encode(PASSWORD);
        addMember(company, "+380677000001", hash, "admin");
        addMember(company, "+380677000002", hash, "manager");
        admin = TestApi.loggedIn(port, "+380677000001", PASSWORD);
        manager = TestApi.loggedIn(port, "+380677000002", PASSWORD);
        Response installed = admin.post("/api/v1/asset-types/install-template", Map.of("templateKey", "exploration-drilling"));
        assertThat(installed.str("positions")).contains("supervisor", "driller", "storekeeper");
        for (Object p : admin.get("/api/v1/positions").list()) {
            Map<?, ?> position = (Map<?, ?>) p;
            positionIds.put((String) position.get("code"), (String) position.get("id"));
        }
    }

    @Test
    void templatePositionsHaveProjectRoles() {
        Map<?, ?> supervisor = position("supervisor");
        assertThat(supervisor.get("projectRole")).isEqualTo("supervisor");
        assertThat(supervisor.get("supervisorCanAdd")).isEqualTo(false);
        assertThat(position("driller_helper").get("supervisorCanAdd")).isEqualTo(true);
        assertThat(position("storekeeper").get("projectRole")).isEqualTo("storekeeper");
        assertThat(manager.post("/api/v1/positions", Map.of("code", "welder", "name", Map.of("en", "Welder"))).status())
                .as("посади змінює catalog.manage").isEqualTo(403);
        assertThat(admin.post("/api/v1/positions", Map.of("code", "welder", "name", Map.of("en", "Welder"))).status())
                .isEqualTo(201);
    }

    @Test
    void createAndUpdatePersonnel() {
        Response created = manager.post("/api/v1/personnel",
                person("Marcos", null, "subcontractor", "Geo Drill Ltda", positionIds.get("driller")));
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.str("employmentType")).isEqualTo("subcontractor");
        assertThat(created.str("loginRole")).isNull();

        assertThat(manager.post("/api/v1/personnel", person("Bad", null, "freelance", null, null)).field("employmentType"))
                .isEqualTo("invalid_option");

        Map<String, Object> update = person("Marcos Silva", null, "staff", null, positionIds.get("driller"));
        update.put("version", 7);
        assertThat(manager.put("/api/v1/personnel/" + created.str("id"), update).status()).isEqualTo(409);
        update.put("version", 1);
        assertThat(manager.put("/api/v1/personnel/" + created.str("id"), update).str("fullName")).isEqualTo("Marcos Silva");
    }

    @Test
    void loginNotMoreThanOwnRights() {
        String id = manager.post("/api/v1/personnel",
                person("Login Person", "+380677000010", "staff", null, positionIds.get("supervisor"))).str("id");
        String path = "/api/v1/personnel/" + id + "/login";

        Response director = manager.post(path, Map.of("role", "director", "password", "login-password"));
        assertThat(director.status()).as("менеджер не видасть роль керівника").isEqualTo(403);
        assertThat(director.str("error")).isEqualTo("role_exceeds_own");
        assertThat(manager.post(path, Map.of("role", "client_rep", "password", "login-password")).field("role"))
                .isEqualTo("invalid_option");

        Response granted = manager.post(path, Map.of("role", "worker", "password", "login-password"));
        assertThat(granted.status()).isEqualTo(200);
        assertThat(granted.at("newAccount")).isEqualTo(true);
        assertThat(granted.str("personnel.loginRole")).isEqualTo("worker");
        assertThat(manager.post(path, Map.of("role", "worker")).status()).isEqualTo(409);
        assertThat(TestApi.loggedIn(port, "+380677000010", "login-password")).isNotNull();

        assertThat(manager.delete(path).str("loginRole")).isNull();
        Response relogin = new TestApi(port).post("/api/v1/auth/login",
                Map.of("phone", "+380677000010", "password", "login-password"));
        assertThat(relogin.str("company")).isNull();
    }

    @Test
    void linksExistingMemberWithoutChangingRole() {
        // Адмін заводить собі запис виконавця: роль лишається «адмін»
        String own = admin.post("/api/v1/personnel",
                person("Admin Himself", "+380677000001", "staff", null, positionIds.get("supervisor"))).str("id");
        Response linked = admin.post("/api/v1/personnel/" + own + "/login", Map.of("role", "worker"));
        assertThat(linked.at("newAccount")).isEqualTo(false);
        assertThat(linked.str("personnel.loginRole")).isEqualTo("admin");

        assertThat(manager.delete("/api/v1/personnel/" + own + "/login").status())
                .as("менеджер не закриє вхід адміну").isEqualTo(403);
        assertThat(admin.delete("/api/v1/personnel/" + own + "/login").str("error")).isEqualTo("cannot_revoke_self");
    }

    private Map<?, ?> position(String code) {
        return admin.get("/api/v1/positions").list().stream().map(p -> (Map<?, ?>) p)
                .filter(p -> code.equals(p.get("code"))).findFirst().orElseThrow();
    }

    static Map<String, Object> person(String fullName, String phone, String employmentType, String organization,
                                      String positionId) {
        Map<String, Object> body = new HashMap<>();
        body.put("fullName", fullName);
        body.put("phone", phone);
        body.put("employmentType", employmentType);
        body.put("organization", organization);
        body.put("positionId", positionId);
        return body;
    }
}
