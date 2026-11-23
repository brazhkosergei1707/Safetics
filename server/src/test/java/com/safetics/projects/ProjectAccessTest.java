package com.safetics.projects;

import static com.safetics.projects.ProjectFixtures.project;
import static org.assertj.core.api.Assertions.assertThat;

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
 * Хто які проєкти бачить: адмін і керівник — усі; менеджер і виконавець — лише з доступом
 * (автор проєкту отримує його одразу); портал — проєкти свого замовника. Правило діє в базі:
 * прямий SQL під роллю застосунку бачить те саме.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProjectAccessTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "access-test-password";

    @Value("${local.server.port}")
    int port;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JdbcClient app;

    UUID company;
    UUID managerId;
    UUID workerId;
    UUID portalId;
    TestApi admin;
    TestApi director;
    TestApi manager;
    TestApi worker;

    String adminProject;
    String managerProject;

    @BeforeAll
    void setUp() {
        company = addCompany("Access Co");
        String hash = passwordEncoder.encode(PASSWORD);
        addMember(company, "+380676000001", hash, "admin");
        addMember(company, "+380676000002", hash, "director");
        managerId = addMember(company, "+380676000003", hash, "manager");
        workerId = addMember(company, "+380676000004", hash, "worker");
        portalId = addMember(company, "+380676000005", hash, "client_rep");
        admin = TestApi.loggedIn(port, "+380676000001", PASSWORD);
        director = TestApi.loggedIn(port, "+380676000002", PASSWORD);
        manager = TestApi.loggedIn(port, "+380676000003", PASSWORD);
        worker = TestApi.loggedIn(port, "+380676000004", PASSWORD);

        adminProject = admin.post("/api/v1/projects", project("ACC-ADMIN", null)).str("id");
        assertThat(manager.post("/api/v1/projects", project("ACC-NOPE", null)).status())
                .as("проєкти створює головний менеджер").isEqualTo(403);
        Map<String, Object> managed = new java.util.HashMap<>(project("ACC-MANAGER", null));
        managed.put("managerId", managerId.toString());
        managerProject = admin.post("/api/v1/projects", managed).str("id");
    }

    @Test
    void adminAndDirectorSeeAllProjects() {
        assertThat(codes(admin)).containsExactlyInAnyOrder("ACC-ADMIN", "ACC-MANAGER");
        assertThat(codes(director)).containsExactlyInAnyOrder("ACC-ADMIN", "ACC-MANAGER");
        assertThat(admin.get("/api/v1/projects/" + managerProject + "/access").list())
                .as("відповідальний менеджер отримав доступ")
                .extracting(a -> (Object) ((Map<?, ?>) a).get("userId")).containsExactly(managerId.toString());
    }

    @Test
    void managerSeesOnlyProjectsWithAccess() {
        assertThat(codes(manager)).containsExactly("ACC-MANAGER");
        assertThat(manager.get("/api/v1/projects/" + adminProject).status()).isEqualTo(404);
        assertThat(manager.get("/api/v1/projects/" + adminProject + "/shift-schedules").status()).isEqualTo(404);
        assertThat(manager.post("/api/v1/projects/" + adminProject + "/access",
                Map.of("userId", managerId.toString())).status()).as("не видасть собі доступ сам").isEqualTo(404);
    }

    @Test
    void grantAndRevokeAccess() {
        String path = "/api/v1/projects/" + adminProject + "/access";
        assertThat(codes(worker)).isEmpty();

        Response granted = admin.post(path, Map.of("userId", workerId.toString()));
        assertThat(granted.status()).isEqualTo(200);
        assertThat(codes(worker)).containsExactly("ACC-ADMIN");
        assertThat(worker.get("/api/v1/projects/" + adminProject + "/shift-schedules").list()).hasSize(1);
        assertThat(worker.get(path).status()).as("перелік доступу — project.manage").isEqualTo(403);

        assertThat(admin.post(path, Map.of("userId", portalId.toString())).field("userId")).isEqualTo("portal_role");
        assertThat(admin.post(path, Map.of("userId", UUID.randomUUID().toString())).field("userId"))
                .isEqualTo("not_member");

        admin.delete(path + "/" + workerId);
        assertThat(codes(worker)).isEmpty();
        assertThat(worker.get("/api/v1/projects/" + adminProject).status()).isEqualTo(404);
    }

    @Test
    void ruleHoldsInDatabase() {
        List<String> asManager = TenantContext.callAs(company, managerId,
                () -> app.sql("select code from project order by code").query(String.class).list());
        assertThat(asManager).containsExactly("ACC-MANAGER");
        Long schedules = TenantContext.callAs(company, managerId,
                () -> app.sql("select count(*) from shift_schedule where project_id = ?::uuid")
                        .param(adminProject).query(Long.class).single());
        assertThat(schedules).isZero();
        int updated = TenantContext.callAs(company, managerId,
                () -> app.sql("update project set name = 'hacked' where id = ?::uuid").param(adminProject).update());
        assertThat(updated).isZero();
    }

    @Test
    void membersListShowsWhoCanGetAccess() {
        Response members = manager.get("/api/v1/members");
        assertThat(members.status()).isEqualTo(200);
        Map<?, ?> portal = members.list().stream().map(m -> (Map<?, ?>) m)
                .filter(m -> portalId.toString().equals(m.get("userId"))).findFirst().orElseThrow();
        assertThat(portal.get("portal")).isEqualTo(true);
        Map<?, ?> director = members.list().stream().map(m -> (Map<?, ?>) m)
                .filter(m -> "director".equals(m.get("role"))).findFirst().orElseThrow();
        assertThat(director.get("allProjects")).isEqualTo(true);
        assertThat(worker.get("/api/v1/members").status()).isEqualTo(403);
    }

    private static List<Object> codes(TestApi api) {
        return api.get("/api/v1/projects").list().stream().map(p -> (Object) ((Map<?, ?>) p).get("code")).toList();
    }
}
