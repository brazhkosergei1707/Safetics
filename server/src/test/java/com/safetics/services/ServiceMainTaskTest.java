package com.safetics.services;

import static com.safetics.projects.ProjectFixtures.project;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
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
 * Послуга і головна задача: створюються разом (ті самі дати, відповідальний або супервайзер),
 * дії з послугою — через задачу, скасування й дати — в обидва боки, парний тип задачі для нового
 * типу послуги, правила типу головної задачі (меблі: заміри → виготовлення → монтаж).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ServiceMainTaskTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "main-task-test-password";

    @Value("${local.server.port}")
    int port;

    @Autowired
    PasswordEncoder passwordEncoder;

    TestApi admin;
    TestApi client;
    TestApi furnitureAdmin;
    UUID supervisorId;
    UUID workerId;
    String holeType;
    String projectA;

    @BeforeAll
    void setUp() {
        UUID company = addCompany("Main Task Co");
        String hash = passwordEncoder.encode(PASSWORD);
        addMember(company, "+380679600001", hash, "admin");
        supervisorId = addMember(company, "+380679600002", hash, "worker");
        workerId = addMember(company, "+380679600003", hash, "worker");
        admin = TestApi.loggedIn(port, "+380679600001", PASSWORD);
        admin.post("/api/v1/asset-types/install-template", Map.of("templateKey", "exploration-drilling"));
        holeType = admin.get("/api/v1/service-types").list().stream().map(t -> (Map<?, ?>) t)
                .filter(t -> "drill_hole".equals(t.get("code"))).findFirst().orElseThrow().get("id").toString();

        String customer = admin.post("/api/v1/customers", Map.of("name", "Main Task Customer")).str("id");
        projectA = admin.post("/api/v1/projects", project("MT-A", customer)).str("id");

        String supervisorPosition = admin.get("/api/v1/positions").list().stream().map(p -> (Map<?, ?>) p)
                .filter(p -> "supervisor".equals(p.get("code"))).findFirst().orElseThrow().get("id").toString();
        Map<String, Object> person = new HashMap<>();
        person.put("fullName", "Main Task Supervisor");
        person.put("phone", "+380679600002");
        person.put("positionId", supervisorPosition);
        String personId = admin.post("/api/v1/personnel", person).str("id");
        admin.post("/api/v1/personnel/" + personId + "/login", Map.of("role", "worker"));
        admin.post("/api/v1/projects/" + projectA + "/personnel-assignments",
                Map.of("personnelId", personId, "startDate", LocalDate.now().minusDays(30).toString()));
        admin.post("/api/v1/projects/" + projectA + "/access", Map.of("userId", workerId.toString()));

        Map<String, Object> contact = new HashMap<>();
        contact.put("fullName", "Main Task Geologist");
        contact.put("phone", "+380679600004");
        String contactId = admin.post("/api/v1/customers/" + customer + "/contacts", contact).str("id");
        admin.post("/api/v1/customer-contacts/" + contactId + "/portal-access",
                Map.of("role", "client_rep", "password", "client-password"));
        client = TestApi.loggedIn(port, "+380679600004", "client-password");

        UUID furniture = addCompany("Main Task Furniture");
        addMember(furniture, "+380679600005", hash, "admin");
        furnitureAdmin = TestApi.loggedIn(port, "+380679600005", PASSWORD);
        furnitureAdmin.post("/api/v1/asset-types/install-template", Map.of("templateKey", "furniture"));
    }

    @Test
    void serviceCreatesMainTaskWithSameDatesAndAssignee() {
        Map<String, Object> body = hole("MT-1");
        body.put("plannedStart", "2026-12-01");
        body.put("plannedEnd", "2026-12-10");
        body.put("assigneeId", workerId.toString());
        Response service = admin.post(services(), body);
        assertThat(service.status()).isEqualTo(201);
        assertThat(service.str("mainTaskId")).isNotNull();
        assertThat(service.str("assigneeId")).isEqualTo(workerId.toString());

        Response task = admin.get("/api/v1/tasks/" + service.str("mainTaskId"));
        assertThat(task.str("typeCode")).isEqualTo("drilling");
        assertThat(task.str("title")).isEqualTo("Perform hole drilling: MT-1");
        assertThat(task.str("serviceId")).isEqualTo(service.str("id"));
        assertThat(task.str("plannedStart")).isEqualTo("2026-12-01");
        assertThat(task.str("plannedEnd")).isEqualTo("2026-12-10");
        assertThat(task.str("assigneeId")).isEqualTo(workerId.toString());
        assertThat(((Number) service.at("mainTaskNumber")).longValue()).isEqualTo(((Number) task.at("number")).longValue());
    }

    @Test
    void supervisorIsDefaultAssigneeAlsoForCustomerPlan() {
        Response byAdmin = admin.post(services(), hole("MT-2"));
        assertThat(byAdmin.str("assigneeId")).as("супервайзер проєкту").isEqualTo(supervisorId.toString());

        Map<String, Object> body = hole("MT-3");
        body.put("assigneeId", workerId.toString());
        Response byClient = client.post(services(), body);
        assertThat(byClient.status()).as("портал створює послугу, задача — від імені системи").isEqualTo(201);
        assertThat(byClient.str("mainTaskId")).as("портал задач не бачить").isNull();
        Response seenByAdmin = admin.get("/api/v1/services/" + byClient.str("id"));
        assertThat(seenByAdmin.str("assigneeId")).as("замовник відповідального не обирає").isEqualTo(supervisorId.toString());
    }

    @Test
    void completeGoesThroughMainTaskClosingForm() {
        Response service = admin.post(services(), hole("MT-4"));
        String id = service.str("id");
        assertThat(admin.post("/api/v1/services/" + id + "/start", Map.of()).str("status")).isEqualTo("in_progress");
        assertThat(admin.get("/api/v1/tasks/" + service.str("mainTaskId")).str("statusCategory")).isEqualTo("active");

        assertThat(admin.post("/api/v1/services/" + id + "/complete", Map.of()).field("closingValues.actualDepth"))
                .isEqualTo("required");
        Response completed = admin.post("/api/v1/services/" + id + "/complete",
                Map.of("closingValues", Map.of("actualDepth", 255, "completionReason", "target_depth")));
        assertThat(completed.str("status")).isEqualTo("completed");
        assertThat(completed.str("attributes.actualDepth")).isEqualTo("255");
        assertThat(admin.get("/api/v1/tasks/" + service.str("mainTaskId")).str("statusCategory")).isEqualTo("done");
    }

    @Test
    void cancelWorksBothWays() {
        Response first = admin.post(services(), hole("MT-5"));
        admin.post("/api/v1/services/" + first.str("id") + "/cancel", Map.of("reason", "Moved"));
        assertThat(admin.get("/api/v1/tasks/" + first.str("mainTaskId")).str("statusCategory")).isEqualTo("cancelled");
        assertThat(admin.post("/api/v1/services/" + first.str("id") + "/reopen", Map.of()).str("status")).isEqualTo("planned");
        assertThat(admin.get("/api/v1/tasks/" + first.str("mainTaskId")).str("statusCategory")).isEqualTo("new");

        Response second = admin.post(services(), hole("MT-6"));
        Response task = admin.get("/api/v1/tasks/" + second.str("mainTaskId"));
        admin.post("/api/v1/tasks/" + task.str("id") + "/status", Map.of("status", "cancelled", "version", task.at("version")));
        Response cancelled = admin.get("/api/v1/services/" + second.str("id"));
        assertThat(cancelled.str("status")).isEqualTo("cancelled");
        assertThat(cancelled.str("cancelReason")).isEqualTo("Task #" + task.at("number") + " cancelled");
    }

    @Test
    void datesAndAssigneeStayInSync() {
        Response service = admin.post(services(), hole("MT-7"));
        Map<String, Object> update = hole("MT-7");
        update.remove("typeId");
        update.put("plannedStart", "2027-01-05");
        update.put("plannedEnd", "2027-01-15");
        update.put("assigneeId", workerId.toString());
        update.put("version", service.at("version"));
        Response updated = admin.put("/api/v1/services/" + service.str("id"), update);
        assertThat(updated.str("assigneeId")).isEqualTo(workerId.toString());
        Response task = admin.get("/api/v1/tasks/" + service.str("mainTaskId"));
        assertThat(task.str("plannedEnd")).isEqualTo("2027-01-15");
        assertThat(task.str("assigneeId")).isEqualTo(workerId.toString());

        Map<String, Object> taskUpdate = new HashMap<>();
        taskUpdate.put("title", task.str("title"));
        taskUpdate.put("assigneeId", task.str("assigneeId"));
        taskUpdate.put("serviceId", service.str("id"));
        taskUpdate.put("plannedStart", "2027-01-05");
        taskUpdate.put("plannedEnd", "2027-01-20");
        taskUpdate.put("version", task.at("version"));
        assertThat(admin.put("/api/v1/tasks/" + task.str("id"), taskUpdate).status()).isEqualTo(200);
        assertThat(admin.get("/api/v1/services/" + service.str("id")).str("plannedEnd"))
                .as("дати головної задачі — у послугу").isEqualTo("2027-01-20");
    }

    @Test
    void newServiceTypeGetsPairedTaskType() {
        Map<String, Object> type = new HashMap<>();
        type.put("code", "pump_test");
        type.put("name", Map.of("en", "Pump test", "uk", "Відкачка"));
        type.put("fields", java.util.List.of());
        Response created = admin.post("/api/v1/service-types", type);
        assertThat(created.status()).isEqualTo(201);
        Map<?, ?> taskType = admin.get("/api/v1/task-types").list().stream().map(t -> (Map<?, ?>) t)
                .filter(t -> created.str("taskTypeId").equals(t.get("id"))).findFirst().orElseThrow();
        assertThat(taskType.get("code")).isEqualTo("perform_pump_test");
        assertThat(((Map<?, ?>) taskType.get("name")).get("en")).isEqualTo("Perform pump test");
        assertThat(((Map<?, ?>) taskType.get("name")).get("uk")).isEqualTo("Виконати відкачка");
        assertThat(taskType.get("subjectKind")).isEqualTo("service");

        String general = admin.get("/api/v1/task-types").list().stream().map(t -> (Map<?, ?>) t)
                .filter(t -> "general".equals(t.get("code"))).findFirst().orElseThrow().get("id").toString();
        Map<String, Object> wrong = new HashMap<>(type);
        wrong.put("code", "pump_test_2");
        wrong.put("taskTypeId", general);
        assertThat(admin.post("/api/v1/service-types", wrong).field("taskTypeId")).isEqualTo("not_service_task_type");
    }

    @Test
    void furnitureOrderRunsMainTaskRules() {
        String project = furnitureAdmin.post("/api/v1/projects", project("MT-SHOP", null)).str("id");
        String orderType = furnitureAdmin.get("/api/v1/service-types").list().stream().map(t -> (Map<?, ?>) t)
                .filter(t -> "furniture_order".equals(t.get("code"))).findFirst().orElseThrow().get("id").toString();
        Map<String, Object> body = new HashMap<>();
        body.put("typeId", orderType);
        body.put("code", "ORD-1");
        body.put("attributes", Map.of("description", "Kitchen"));
        Response order = furnitureAdmin.post("/api/v1/projects/" + project + "/services", body);
        assertThat(order.status()).isEqualTo(201);
        Response task = furnitureAdmin.get("/api/v1/tasks/" + order.str("mainTaskId"));
        assertThat(task.str("typeCode")).isEqualTo("order");
        assertThat(task.at("childrenTotal")).as("заміри, виготовлення, монтаж").isEqualTo(3);
    }

    private String services() {
        return "/api/v1/projects/" + projectA + "/services";
    }

    private Map<String, Object> hole(String code) {
        Map<String, Object> body = new HashMap<>();
        body.put("typeId", holeType);
        body.put("code", code);
        body.put("attributes", Map.of("plannedDepth", 300, "dip", -60, "coreSize", "HQ"));
        return body;
    }
}
