package com.safetics.tasks;

import static com.safetics.projects.ProjectFixtures.project;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.HashMap;
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

/**
 * Задачі: типи з шаблону, права (менеджер, супервайзер, виконавець), переходи за категоріями,
 * «блокує» і цикли, підзадачі, форма закриття, синхронізація з послугою, приховування від порталу.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TaskApiTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "tasks-test-password";

    @Value("${local.server.port}")
    int port;

    @Autowired
    PasswordEncoder passwordEncoder;

    TestApi admin;
    TestApi supervisor;
    TestApi assignee;
    TestApi outsider;
    TestApi client;
    UUID assigneeId;
    UUID outsiderId;
    String projectA;
    String projectB;
    Map<String, String> typeIds = new HashMap<>();
    String holeType;

    @BeforeAll
    void setUp() {
        UUID company = addCompany("Tasks Co");
        String hash = passwordEncoder.encode(PASSWORD);
        addMember(company, "+380679400001", hash, "admin");
        addMember(company, "+380679400002", hash, "worker");
        assigneeId = addMember(company, "+380679400003", hash, "worker");
        outsiderId = addMember(company, "+380679400005", hash, "worker");
        admin = TestApi.loggedIn(port, "+380679400001", PASSWORD);
        admin.post("/api/v1/asset-types/install-template", Map.of("templateKey", "exploration-drilling"));
        for (Object t : admin.get("/api/v1/task-types").list()) {
            typeIds.put((String) ((Map<?, ?>) t).get("code"), (String) ((Map<?, ?>) t).get("id"));
        }
        holeType = admin.get("/api/v1/service-types").list().stream().map(t -> (Map<?, ?>) t)
                .filter(t -> "drill_hole".equals(t.get("code"))).findFirst().orElseThrow().get("id").toString();

        String customer = admin.post("/api/v1/customers", Map.of("name", "Tasks Customer")).str("id");
        projectA = admin.post("/api/v1/projects", project("TSK-A", customer)).str("id");
        projectB = admin.post("/api/v1/projects", project("TSK-B", null)).str("id");

        String supervisorPosition = admin.get("/api/v1/positions").list().stream().map(p -> (Map<?, ?>) p)
                .filter(p -> "supervisor".equals(p.get("code"))).findFirst().orElseThrow().get("id").toString();
        Map<String, Object> person = new HashMap<>();
        person.put("fullName", "Tasks Supervisor");
        person.put("phone", "+380679400002");
        person.put("positionId", supervisorPosition);
        String personId = admin.post("/api/v1/personnel", person).str("id");
        admin.post("/api/v1/personnel/" + personId + "/login", Map.of("role", "worker"));
        admin.post("/api/v1/projects/" + projectA + "/personnel-assignments",
                Map.of("personnelId", personId, "startDate", LocalDate.now().minusDays(30).toString()));
        admin.post("/api/v1/projects/" + projectA + "/access", Map.of("userId", assigneeId.toString()));

        Map<String, Object> contact = new HashMap<>();
        contact.put("fullName", "Tasks Client");
        contact.put("phone", "+380679400004");
        String contactId = admin.post("/api/v1/customers/" + customer + "/contacts", contact).str("id");
        admin.post("/api/v1/customer-contacts/" + contactId + "/portal-access",
                Map.of("role", "client_rep", "password", "client-password"));

        supervisor = TestApi.loggedIn(port, "+380679400002", PASSWORD);
        assignee = TestApi.loggedIn(port, "+380679400003", PASSWORD);
        outsider = TestApi.loggedIn(port, "+380679400005", PASSWORD);
        client = TestApi.loggedIn(port, "+380679400004", "client-password");
    }

    @Test
    void typesComeFromTemplate() {
        assertThat(typeIds).containsKeys("drilling", "service", "general");
        Map<?, ?> drilling = admin.get("/api/v1/task-types").list().stream().map(t -> (Map<?, ?>) t)
                .filter(t -> "drilling".equals(t.get("code"))).findFirst().orElseThrow();
        assertThat(drilling.get("syncService")).isEqualTo(true);
        assertThat(drilling.get("subjectKind")).isEqualTo("service");
    }

    @Test
    void whoCanCreateAndSee() {
        Response created = admin.post(tasks(projectA), general("By admin", assigneeId));
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.str("status")).isEqualTo("new");
        assertThat(((Number) created.at("number")).longValue()).isPositive();
        assertThat(supervisor.post(tasks(projectA), general("By supervisor", null)).status()).isEqualTo(201);
        assertThat(assignee.post(tasks(projectA), general("By assignee", null)).status()).isEqualTo(403);

        assertThat(admin.post(tasks(projectA), general("To outsider", outsiderId)).field("assigneeId"))
                .isEqualTo("no_project_access");
        assertThat(outsider.get(tasks(projectA)).status()).as("проєкт не видно").isEqualTo(404);
        assertThat(client.get(tasks(projectA)).status()).as("портал не бачить задач").isEqualTo(403);
        assertThat(assignee.get("/api/v1/tasks/my").list())
                .extracting(t -> (Object) ((Map<?, ?>) t).get("title")).contains("By admin");
    }

    @Test
    void assigneeChangesOnlyStatusAndDates() {
        Response task = admin.post(tasks(projectA), general("Assignee task", assigneeId));
        String id = task.str("id");

        Map<String, Object> dates = update(task, "Assignee task");
        dates.put("plannedEnd", LocalDate.now().plusDays(5).toString());
        Response moved = assignee.put("/api/v1/tasks/" + id, dates);
        assertThat(moved.status()).isEqualTo(200);
        assertThat(assignee.put("/api/v1/tasks/" + id, update(moved, "Renamed")).str("error")).isEqualTo("assignee_dates_only");

        Response active = assignee.post(status(id), Map.of("status", "in_progress", "version", moved.at("version")));
        assertThat(active.str("statusCategory")).isEqualTo("active");
        assertThat(active.str("actualStart")).isNotNull();
        assertThat(assignee.post(status(id), Map.of("status", "cancelled", "version", active.at("version"))).str("error"))
                .isEqualTo("cancel_not_allowed");
        Response done = assignee.post(status(id), Map.of("status", "done", "version", active.at("version")));
        assertThat(done.str("statusCategory")).isEqualTo("done");
        assertThat(done.str("actualEnd")).isNotNull();
        assertThat(assignee.post(status(id), Map.of("status", "new", "version", done.at("version"))).str("error"))
                .isEqualTo("reopen_not_allowed");
        assertThat(supervisor.post(status(id), Map.of("status", "in_progress", "version", done.at("version")))
                .str("actualEnd")).isNull();

        List<Object> history = admin.get("/api/v1/tasks/" + id + "/history").list();
        assertThat(history).extracting(h -> (Object) ((Map<?, ?>) h).get("action"))
                .contains("create", "update", "status");
    }

    @Test
    void blockingLinksAndCycles() {
        Response first = admin.post(tasks(projectA), general("Checklist", null));
        Response second = admin.post(tasks(projectA), general("Shift report", null));
        Response links = admin.post("/api/v1/tasks/" + first.str("id") + "/links",
                Map.of("taskId", second.str("id"), "kind", "blocks"));
        assertThat((List<?>) links.at("blocks")).hasSize(1);

        assertThat(admin.post(status(second.str("id")), Map.of("status", "in_progress", "version", 1)).str("error"))
                .isEqualTo("blocked");
        assertThat(admin.post("/api/v1/tasks/" + second.str("id") + "/links",
                Map.of("taskId", first.str("id"), "kind", "blocks")).field("taskId")).isEqualTo("cycle");

        admin.post(status(first.str("id")), Map.of("status", "done", "version", 1));
        assertThat(admin.post(status(second.str("id")), Map.of("status", "in_progress", "version", 1)).str("status"))
                .isEqualTo("in_progress");
    }

    @Test
    void parentWaitsForChildrenWhenTypeSaysSo() {
        Map<String, Object> type = new HashMap<>();
        type.put("code", "order_like");
        type.put("name", Map.of("en", "Order-like"));
        type.put("statuses", List.of(
                Map.of("code", "new", "label", Map.of("en", "New"), "category", "new"),
                Map.of("code", "done", "label", Map.of("en", "Done"), "category", "done")));
        type.put("childrenBlockClose", true);
        String typeId = admin.post("/api/v1/task-types", type).str("id");
        assertThat(typeId).isNotNull();

        Map<String, Object> parentBody = general("Parent", null);
        parentBody.put("typeId", typeId);
        String parent = admin.post(tasks(projectA), parentBody).str("id");
        Map<String, Object> childBody = general("Child", null);
        childBody.put("parentId", parent);
        String child = admin.post(tasks(projectA), childBody).str("id");

        assertThat(admin.get("/api/v1/tasks/" + parent).at("childrenOpen")).isEqualTo(1);
        assertThat(admin.post(status(parent), Map.of("status", "done", "version", 1)).str("error")).isEqualTo("open_children");
        admin.post(status(child), Map.of("status", "done", "version", 1));
        assertThat(admin.post(status(parent), Map.of("status", "done", "version", 1)).str("status")).isEqualTo("done");

        Map<String, Object> other = general("Other project child", null);
        other.put("parentId", parent);
        assertThat(admin.post(tasks(projectB), other).field("parentId")).isEqualTo("other_project");
    }

    @Test
    void closingFormCompletesService() {
        Map<String, Object> hole = new HashMap<>();
        hole.put("typeId", holeType);
        hole.put("code", "TSK-BH-1");
        hole.put("attributes", Map.of("plannedDepth", 300));
        String serviceId = admin.post("/api/v1/projects/" + projectA + "/services", hole).str("id");

        Map<String, Object> noSubject = general("Drill without hole", null);
        noSubject.put("typeId", typeIds.get("drilling"));
        assertThat(admin.post(tasks(projectA), noSubject).field("serviceId")).isEqualTo("required");

        Map<String, Object> body = general("Drill TSK-BH-1", null);
        body.put("typeId", typeIds.get("drilling"));
        body.put("serviceId", serviceId);
        assertThat(admin.post(tasks(projectB), body).field("serviceId")).isEqualTo("other_project");
        String id = admin.post(tasks(projectA), body).str("id");

        supervisor.post(status(id), Map.of("status", "in_progress", "version", 1));
        assertThat(admin.get("/api/v1/services/" + serviceId).str("status")).isEqualTo("in_progress");

        Response missing = supervisor.post(status(id), Map.of("status", "done", "version", 2));
        assertThat(missing.field("closingValues.actualDepth")).isEqualTo("required");
        Response done = supervisor.post(status(id), Map.of("status", "done", "version", 2,
                "closingValues", Map.of("actualDepth", 312.5, "completionReason", "target_depth")));
        assertThat(done.str("status")).isEqualTo("done");
        assertThat(done.str("closingValues.actualDepth")).isEqualTo("312.5");

        Response service = admin.get("/api/v1/services/" + serviceId);
        assertThat(service.str("status")).isEqualTo("completed");
        assertThat(service.str("attributes.actualDepth")).as("фактична глибина — у паспорт свердловини").isEqualTo("312.5");
        assertThat(admin.get(tasks(projectA) + "?serviceId=" + serviceId).list()).as("головна + окрема").hasSize(2);
    }

    private String tasks(String projectId) {
        return "/api/v1/projects/" + projectId + "/tasks";
    }

    private static String status(String id) {
        return "/api/v1/tasks/" + id + "/status";
    }

    private Map<String, Object> general(String title, UUID assignee) {
        Map<String, Object> body = new HashMap<>();
        body.put("typeId", typeIds.get("general"));
        body.put("title", title);
        body.put("assigneeId", assignee == null ? null : assignee.toString());
        return body;
    }

    private static Map<String, Object> update(Response task, String title) {
        Map<String, Object> body = new HashMap<>();
        body.put("title", title);
        body.put("assigneeId", task.str("assigneeId"));
        body.put("plannedStart", task.str("plannedStart"));
        body.put("plannedEnd", task.str("plannedEnd"));
        body.put("version", task.at("version"));
        return body;
    }
}
