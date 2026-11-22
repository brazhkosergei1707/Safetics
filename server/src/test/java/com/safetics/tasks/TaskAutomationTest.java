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
 * Автоматизація задач: «Замовлення» меблів створює ланцюжок «Замір → Виготовлення → Монтаж» на
 * супервайзера; правило за категорією статусу; обмеження глибини; без супервайзера — без виконавця.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TaskAutomationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "automation-test-password";

    @Value("${local.server.port}")
    int port;

    @Autowired
    PasswordEncoder passwordEncoder;

    TestApi admin;
    UUID supervisorId;
    String projectWithCrew;
    String projectEmpty;
    String orderServiceType;
    Map<String, String> typeIds = new HashMap<>();

    @BeforeAll
    void setUp() {
        UUID company = addCompany("Automation Furniture Co");
        String hash = passwordEncoder.encode(PASSWORD);
        addMember(company, "+380679500001", hash, "admin");
        supervisorId = addMember(company, "+380679500002", hash, "worker");
        admin = TestApi.loggedIn(port, "+380679500001", PASSWORD);
        admin.post("/api/v1/asset-types/install-template", Map.of("templateKey", "furniture"));
        for (Object t : admin.get("/api/v1/task-types").list()) {
            typeIds.put((String) ((Map<?, ?>) t).get("code"), (String) ((Map<?, ?>) t).get("id"));
        }
        orderServiceType = admin.get("/api/v1/service-types").list().stream().map(t -> (Map<?, ?>) t)
                .filter(t -> "furniture_order".equals(t.get("code"))).findFirst().orElseThrow().get("id").toString();
        projectWithCrew = admin.post("/api/v1/projects", project("AUTO-BASE", null)).str("id");
        projectEmpty = admin.post("/api/v1/projects", project("AUTO-EMPTY", null)).str("id");

        String supervisorPosition = admin.get("/api/v1/positions").list().stream().map(p -> (Map<?, ?>) p)
                .filter(p -> "supervisor".equals(p.get("code"))).findFirst().orElseThrow().get("id").toString();
        Map<String, Object> person = new HashMap<>();
        person.put("fullName", "Foreman");
        person.put("phone", "+380679500002");
        person.put("positionId", supervisorPosition);
        String personId = admin.post("/api/v1/personnel", person).str("id");
        admin.post("/api/v1/personnel/" + personId + "/login", Map.of("role", "worker"));
        admin.post("/api/v1/projects/" + projectWithCrew + "/personnel-assignments",
                Map.of("personnelId", personId, "startDate", LocalDate.now().minusDays(30).toString()));
    }

    @Test
    void orderCreatesChainOfSubtasks() {
        String service = order(projectWithCrew, "2026-014");
        Response order = admin.post(tasks(projectWithCrew), Map.of("typeId", typeIds.get("order"), "title", "School furniture",
                "serviceId", service));
        assertThat(order.status()).isEqualTo(201);
        assertThat(order.at("childrenTotal")).isEqualTo(3);

        Map<?, ?> links = admin.get("/api/v1/tasks/" + order.str("id") + "/links").map();
        List<Map<?, ?>> children = ((List<?>) links.get("children")).stream().<Map<?, ?>>map(c -> (Map<?, ?>) c).toList();
        assertThat(children).extracting(c -> (Object) c.get("title"))
                .containsExactly("Measurement: 2026-014", "Manufacturing: 2026-014", "Installation: 2026-014");

        Response measurement = admin.get("/api/v1/tasks/" + children.get(0).get("id"));
        assertThat(measurement.str("assigneeId")).isEqualTo(supervisorId.toString());
        assertThat(measurement.str("plannedEnd")).isNotNull();
        assertThat(measurement.at("openBlockers")).isEqualTo(0);
        Response manufacturing = admin.get("/api/v1/tasks/" + children.get(1).get("id"));
        assertThat(manufacturing.at("openBlockers")).as("чекає на замір").isEqualTo(1);
        Response installation = admin.get("/api/v1/tasks/" + children.get(2).get("id"));
        assertThat(admin.post("/api/v1/tasks/" + installation.str("id") + "/status",
                Map.of("status", "in_progress", "version", 1)).str("error")).isEqualTo("blocked");

        assertThat(admin.get("/api/v1/tasks/" + order.str("id") + "/history").list())
                .extracting(h -> (Object) ((Map<?, ?>) h).get("action")).contains("create", "automation");
        assertThat(admin.post("/api/v1/tasks/" + order.str("id") + "/status",
                Map.of("status", "done", "version", order.at("version"))).str("error")).isEqualTo("open_children");
    }

    @Test
    void withoutSupervisorSubtasksHaveNoAssignee() {
        String service = order(projectEmpty, "2026-015");
        String id = admin.post(tasks(projectEmpty), Map.of("typeId", typeIds.get("order"), "title", "Kitchen",
                "serviceId", service)).str("id");
        List<?> children = (List<?>) admin.get("/api/v1/tasks/" + id + "/links").at("children");
        assertThat(children).hasSize(3);
        Response first = admin.get("/api/v1/tasks/" + ((Map<?, ?>) children.getFirst()).get("id"));
        assertThat(first.str("assigneeId")).isNull();
        assertThat(admin.get("/api/v1/tasks/" + first.str("id") + "/history").list().toString()).contains("assigneeNotFound");
    }

    @Test
    void ruleOnStatusCategory() {
        Map<String, Object> type = taskType("inspection", List.of(Map.of("trigger", "status_category", "category", "done",
                "taskType", "general", "title", "Follow-up after {number}", "assignTo", "author")));
        String typeId = admin.post("/api/v1/task-types", type).str("id");
        Response task = admin.post(tasks(projectWithCrew), Map.of("typeId", typeId, "title", "Inspect workshop"));
        assertThat(task.at("childrenTotal")).isEqualTo(0);
        Response done = admin.post("/api/v1/tasks/" + task.str("id") + "/status", Map.of("status", "done", "version", 1));
        assertThat(done.at("childrenTotal")).isEqualTo(1);
        Map<?, ?> child = (Map<?, ?>) ((List<?>) admin.get("/api/v1/tasks/" + task.str("id") + "/links").at("children")).getFirst();
        assertThat(child.get("title")).isEqualTo("Follow-up after #" + task.at("number"));
    }

    @Test
    void depthIsLimited() {
        Map<String, Object> type = taskType("loop", List.of(Map.of("trigger", "created", "taskType", "loop")));
        String typeId = admin.post("/api/v1/task-types", type).str("id");
        admin.post(tasks(projectEmpty), Map.of("typeId", typeId, "title", "Root"));
        long loops = admin.get(tasks(projectEmpty)).list().stream().map(t -> (Map<?, ?>) t)
                .filter(t -> "loop".equals(t.get("typeCode"))).count();
        assertThat(loops).as("корінь + %d рівні", TaskWorkflow.MAX_RULE_DEPTH).isEqualTo(1 + TaskWorkflow.MAX_RULE_DEPTH);
    }

    @Test
    void rulesAreValidated() {
        Map<String, Object> forward = taskType("bad_rules", List.of(
                Map.of("trigger", "created", "taskType", "general", "blockedBy", 1),
                Map.of("trigger", "created", "taskType", "general")));
        assertThat(admin.post("/api/v1/task-types", forward).field("rules[0].blockedBy")).isEqualTo("invalid_option");
        Map<String, Object> noCategory = taskType("bad_rules2", List.of(Map.of("trigger", "status_category", "taskType", "general")));
        assertThat(admin.post("/api/v1/task-types", noCategory).field("rules[0].category")).isEqualTo("required");
    }

    private String order(String projectId, String code) {
        Map<String, Object> body = new HashMap<>();
        body.put("typeId", orderServiceType);
        body.put("code", code);
        body.put("attributes", Map.of("description", "Order " + code));
        return admin.post("/api/v1/projects/" + projectId + "/services", body).str("id");
    }

    private static String tasks(String projectId) {
        return "/api/v1/projects/" + projectId + "/tasks";
    }

    private static Map<String, Object> taskType(String code, List<Map<String, Object>> rules) {
        Map<String, Object> type = new HashMap<>();
        type.put("code", code);
        type.put("name", Map.of("en", code));
        type.put("statuses", List.of(
                Map.of("code", "new", "label", Map.of("en", "New"), "category", "new"),
                Map.of("code", "done", "label", Map.of("en", "Done"), "category", "done")));
        type.put("rules", rules);
        return type;
    }
}
