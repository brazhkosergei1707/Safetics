package com.safetics.core.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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

/** Користувачі компанії: додати, змінити роль, вимкнути й відновити — лише адмін (staff.manage). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UsersApiTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "users-test-password";

    @Value("${local.server.port}")
    int port;

    @Autowired
    PasswordEncoder passwordEncoder;

    UUID adminId;
    UUID workerId;
    UUID portalId;
    TestApi admin;
    TestApi manager;

    @BeforeAll
    void setUp() {
        UUID company = addCompany("Users Co");
        String hash = passwordEncoder.encode(PASSWORD);
        adminId = addMember(company, "+380679200001", hash, "admin");
        addMember(company, "+380679200002", hash, "manager");
        workerId = addMember(company, "+380679200003", hash, "worker");
        portalId = addMember(company, "+380679200004", hash, "client_rep");
        admin = TestApi.loggedIn(port, "+380679200001", PASSWORD);
        manager = TestApi.loggedIn(port, "+380679200002", PASSWORD);
    }

    @Test
    void addUserWithRole() {
        assertThat(admin.post("/api/v1/users", Map.of("phone", "+380679200010", "fullName", "Director", "role", "director"))
                .field("password")).isEqualTo("required");
        assertThat(admin.post("/api/v1/users", Map.of("phone", "+380679200010", "fullName", "Director",
                "role", "client_rep", "password", "director-pass")).field("role")).isEqualTo("invalid_option");
        Response added = admin.post("/api/v1/users", Map.of("phone", "+380679200010", "fullName", "Director",
                "role", "director", "password", "director-pass"));
        assertThat(added.status()).isEqualTo(201);
        assertThat(added.str("role")).isEqualTo("director");
        assertThat(TestApi.loggedIn(port, "+380679200010", "director-pass").get("/api/v1/auth/me").str("company.role"))
                .isEqualTo("director");
        assertThat(admin.post("/api/v1/users", Map.of("phone", "+380679200010", "fullName", "Again", "role", "manager"))
                .status()).isEqualTo(409);
        assertThat(admin.post("/api/v1/users", Map.of("phone", "+380679200011", "fullName", "Worker",
                "role", "worker", "password", "worker-pass")).field("role")).as("виконавців додають менеджери").isEqualTo("invalid_option");
        Response head = admin.post("/api/v1/users", Map.of("phone", "+380679200012", "fullName", "Head",
                "role", "head_manager", "password", "head-pass"));
        assertThat(head.str("role")).isEqualTo("head_manager");
    }

    @Test
    void changeRoleDisableEnable() {
        String path = "/api/v1/users/" + workerId;
        assertThat(admin.put(path + "/role", Map.of("role", "manager")).str("role")).isEqualTo("manager");
        assertThat(admin.put(path + "/role", Map.of("role", "superuser")).field("role")).isEqualTo("invalid_option");

        assertThat(admin.post(path + "/disable", Map.of()).str("status")).isEqualTo("disabled");
        Response login = new TestApi(port).post("/api/v1/auth/login", Map.of("phone", "+380679200003", "password", PASSWORD));
        assertThat(login.at("company")).isNull();
        assertThat(admin.post(path + "/enable", Map.of()).str("status")).isEqualTo("active");
        assertThat(TestApi.loggedIn(port, "+380679200003", PASSWORD).get("/api/v1/auth/me").at("company")).isNotNull();
    }

    @Test
    void guards() {
        assertThat(manager.get("/api/v1/users").status()).as("staff.manage — лише адмін").isEqualTo(403);
        assertThat(admin.put("/api/v1/users/" + adminId + "/role", Map.of("role", "manager")).str("error"))
                .isEqualTo("cannot_change_self");
        assertThat(admin.post("/api/v1/users/" + portalId + "/disable", Map.of()).str("error")).isEqualTo("portal_member");
        List<?> users = admin.get("/api/v1/users").list();
        assertThat(users).hasSizeGreaterThanOrEqualTo(2);
        assertThat(users).as("лише офісні ролі: без виконавців і порталу").extracting(u -> (Object) ((Map<?, ?>) u).get("role"))
                .doesNotContain("worker", "client_rep");
    }
}
