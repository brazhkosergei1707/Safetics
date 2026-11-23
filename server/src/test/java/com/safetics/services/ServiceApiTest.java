package com.safetics.services;

import static com.safetics.projects.ProjectFixtures.project;
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
 * Послуги проєкту: поля за типом, статуси, права менеджера, супервайзера проєкту й замовника,
 * замовник послуги в постійному проєкті, видимість.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ServiceApiTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "services-test-password";

    @Value("${local.server.port}")
    int port;

    @Autowired
    PasswordEncoder passwordEncoder;

    TestApi admin;
    /** Виконавців заводить головний менеджер (адмін не має personnel.manage) */
    TestApi hr;
    TestApi supervisor;
    TestApi otherWorker;
    TestApi client;
    String holeType;
    String customerA;
    String customerB;
    String projectA;
    String projectOther;
    String permanentProject;

    @BeforeAll
    void setUp() {
        UUID company = addCompany("Services Co");
        String hash = passwordEncoder.encode(PASSWORD);
        addMember(company, "+380679300001", hash, "admin");
        addMember(company, "+380679300099", hash, "head_manager");
        addMember(company, "+380679300002", hash, "worker");
        UUID other = addMember(company, "+380679300003", hash, "worker");
        admin = TestApi.loggedIn(port, "+380679300001", PASSWORD);
        hr = TestApi.loggedIn(port, "+380679300099", PASSWORD);
        admin.post("/api/v1/asset-types/install-template", Map.of("templateKey", "exploration-drilling"));
        holeType = admin.get("/api/v1/service-types").list().stream().map(t -> (Map<?, ?>) t)
                .filter(t -> "drill_hole".equals(t.get("code"))).findFirst().orElseThrow().get("id").toString();

        customerA = admin.post("/api/v1/customers", Map.of("name", "Services Customer A")).str("id");
        customerB = admin.post("/api/v1/customers", Map.of("name", "Services Customer B")).str("id");
        projectA = admin.post("/api/v1/projects", project("SRV-A", customerA)).str("id");
        projectOther = admin.post("/api/v1/projects", project("SRV-B", customerB)).str("id");
        permanentProject = admin.post("/api/v1/projects", project("SRV-PERM", null)).str("id");

        // Супервайзер проєкту A
        String supervisorPosition = admin.get("/api/v1/positions").list().stream().map(p -> (Map<?, ?>) p)
                .filter(p -> "supervisor".equals(p.get("code"))).findFirst().orElseThrow().get("id").toString();
        Map<String, Object> person = new HashMap<>();
        person.put("fullName", "Services Supervisor");
        person.put("phone", "+380679300002");
        person.put("positionId", supervisorPosition);
        String personId = hr.post("/api/v1/personnel", person).str("id");
        hr.post("/api/v1/personnel/" + personId + "/login", Map.of());
        admin.post("/api/v1/projects/" + projectA + "/personnel-assignments",
                Map.of("personnelId", personId, "startDate", java.time.LocalDate.now().minusDays(30).toString()));
        supervisor = TestApi.loggedIn(port, "+380679300002", PASSWORD);

        // Інший виконавець бачить A, але не супервайзер
        admin.post("/api/v1/projects/" + projectA + "/access", Map.of("userId", other.toString()));
        otherWorker = TestApi.loggedIn(port, "+380679300003", PASSWORD);

        // Представник замовника A
        Map<String, Object> contact = new HashMap<>();
        contact.put("fullName", "Customer Geologist");
        contact.put("phone", "+380679300004");
        String contactId = admin.post("/api/v1/customers/" + customerA + "/contacts", contact).str("id");
        admin.post("/api/v1/customer-contacts/" + contactId + "/portal-access",
                Map.of("role", "client_rep", "password", "client-password"));
        client = TestApi.loggedIn(port, "+380679300004", "client-password");
    }

    @Test
    void lifecycleByManager() {
        Response created = admin.post(services(projectA), hole("BH-1", 300));
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.str("status")).isEqualTo("planned");
        assertThat(created.str("customerName")).as("замовник проєкту").isEqualTo("Services Customer A");
        String id = created.str("id");

        assertThat(admin.post("/api/v1/services/" + id + "/complete", Map.of()).str("error")).isEqualTo("invalid_transition");
        Response started = admin.post("/api/v1/services/" + id + "/start", Map.of());
        assertThat(started.str("status")).isEqualTo("in_progress");
        assertThat(started.str("actualStart")).isNotNull();
        assertThat(admin.post("/api/v1/services/" + id + "/complete", Map.of()).field("closingValues.actualDepth"))
                .as("форма закриття головної задачі").isEqualTo("required");
        Response completed = admin.post("/api/v1/services/" + id + "/complete",
                Map.of("closingValues", Map.of("actualDepth", 298, "completionReason", "target_depth")));
        assertThat(completed.str("status")).isEqualTo("completed");
        assertThat(completed.str("actualEnd")).isNotNull();
        assertThat(completed.str("attributes.actualDepth")).isEqualTo("298");

        Map<String, Object> update = hole("BH-1", 320);
        update.put("version", completed.at("version"));
        assertThat(admin.put("/api/v1/services/" + id, update).str("error")).isEqualTo("service_closed");

        assertThat(admin.post("/api/v1/services/" + id + "/reopen", Map.of()).str("status")).isEqualTo("in_progress");
        assertThat(admin.post("/api/v1/services/" + id + "/cancel", Map.of()).status()).isEqualTo(400);
        Response cancelled = admin.post("/api/v1/services/" + id + "/cancel", Map.of("reason", "Moved by geologist"));
        assertThat(cancelled.str("status")).isEqualTo("cancelled");
        assertThat(cancelled.str("cancelReason")).isEqualTo("Moved by geologist");
        assertThat(admin.post("/api/v1/services/" + id + "/reopen", Map.of()).str("status")).isEqualTo("planned");
    }

    @Test
    void fieldsFollowServiceType() {
        Map<String, Object> missing = hole("BH-V1", 300);
        missing.put("attributes", Map.of("dip", -60));
        assertThat(admin.post(services(projectA), missing).field("attributes.plannedDepth")).isEqualTo("required");

        Map<String, Object> steep = hole("BH-V2", 300);
        steep.put("attributes", Map.of("plannedDepth", 300, "dip", 120));
        assertThat(admin.post(services(projectA), steep).field("attributes.dip")).isEqualTo("too_large");

        assertThat(admin.post(services(projectA), hole("BH-DUP", 100)).status()).isEqualTo(201);
        assertThat(admin.post(services(projectA), hole("bh-dup", 100)).status()).isEqualTo(409);
        assertThat(admin.post(services(projectOther), hole("BH-DUP", 100)).status())
                .as("номер унікальний у межах проєкту").isEqualTo(201);

        Response updated = admin.put("/api/v1/services/" + admin.post(services(projectA), hole("BH-UPD", 100)).str("id"),
                withVersion(hole("BH-UPD", 150), 1));
        assertThat(updated.str("attributes.plannedDepth")).isEqualTo("150");
    }

    @Test
    void supervisorWorksOnOwnProject() {
        Response created = supervisor.post(services(projectA), hole("BH-S1", 200));
        assertThat(created.status()).isEqualTo(201);
        assertThat(supervisor.post("/api/v1/services/" + created.str("id") + "/start", Map.of()).str("status"))
                .isEqualTo("in_progress");

        assertThat(otherWorker.post(services(projectA), hole("BH-S2", 200)).status())
                .as("бачить проєкт, але не супервайзер").isEqualTo(403);
        assertThat(otherWorker.post("/api/v1/services/" + created.str("id") + "/complete", Map.of()).status()).isEqualTo(403);
        assertThat(supervisor.post(services(projectOther), hole("BH-S3", 200)).status()).isEqualTo(404);
    }

    @Test
    void customerPlansOnlyPlannedServicesOnOwnProjects() {
        Response planned = client.post(services(projectA), hole("BH-C1", 250));
        assertThat(planned.status()).as("геолог передає план буріння").isEqualTo(201);
        String id = planned.str("id");
        assertThat(client.post("/api/v1/services/" + id + "/start", Map.of()).status()).isEqualTo(403);
        assertThat(client.put("/api/v1/services/" + id, withVersion(hole("BH-C1", 260), 1)).status()).isEqualTo(200);

        String inProgress = client.post(services(projectA), hole("BH-C2", 250)).str("id");
        supervisor.post("/api/v1/services/" + inProgress + "/start", Map.of());
        assertThat(client.post("/api/v1/services/" + inProgress + "/cancel", Map.of("reason", "x")).str("error"))
                .isEqualTo("only_planned");
        assertThat(client.post("/api/v1/services/" + id + "/cancel", Map.of("reason", "Not needed")).str("status"))
                .isEqualTo("cancelled");

        assertThat(client.get(services(projectOther)).status()).as("проєкт іншого замовника").isEqualTo(404);
        assertThat(client.get(services(projectA)).list()).isNotEmpty();
    }

    @Test
    void customerOfServiceOnlyInPermanentProject() {
        Map<String, Object> wrong = hole("BH-K1", 100);
        wrong.put("customerId", customerB);
        assertThat(admin.post(services(projectA), wrong).field("customerId")).isEqualTo("project_has_customer");

        Map<String, Object> own = hole("ORDER-1", 100);
        own.put("customerId", customerB);
        Response created = admin.post(services(permanentProject), own);
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.str("customerName")).isEqualTo("Services Customer B");
    }

    private String services(String projectId) {
        return "/api/v1/projects/" + projectId + "/services";
    }

    private Map<String, Object> hole(String code, int depth) {
        Map<String, Object> body = new HashMap<>();
        body.put("typeId", holeType);
        body.put("code", code);
        body.put("attributes", Map.of("plannedDepth", depth, "dip", -60, "coreSize", "HQ"));
        return body;
    }

    private static Map<String, Object> withVersion(Map<String, Object> body, int version) {
        body.remove("typeId");
        body.put("version", version);
        return body;
    }
}
