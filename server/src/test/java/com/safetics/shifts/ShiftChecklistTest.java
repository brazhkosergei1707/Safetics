package com.safetics.shifts;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
 * Чек-лист зміни: пункти з типу обладнання, перевірки відповідей, подання знімає блок з рапорту,
 * не пройдено → «Сервіс» (high для критичного, medium — ні) і алерт менеджеру, сервісний статус
 * обладнання, права.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShiftChecklistTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "checklist-test-password";

    @Value("${local.server.port}")
    int port;

    @Autowired
    PasswordEncoder passwordEncoder;

    TestApi admin;
    TestApi manager;
    TestApi other;
    String project;
    String rig;

    @BeforeAll
    void setUp() {
        UUID company = addCompany("Checklist Co");
        String hash = passwordEncoder.encode(PASSWORD);
        addMember(company, "+380679920001", hash, "admin");
        UUID managerId = addMember(company, "+380679920002", hash, "manager");
        UUID otherId = addMember(company, "+380679920003", hash, "worker");
        admin = TestApi.loggedIn(port, "+380679920001", PASSWORD);
        admin.post("/api/v1/asset-types/install-template", Map.of("templateKey", "exploration-drilling"));
        String rigType = admin.get("/api/v1/asset-types").list().stream().<Map<?, ?>>map(t -> (Map<?, ?>) t)
                .filter(t -> "drill_rig".equals(t.get("code"))).findFirst().orElseThrow().get("id").toString();
        String customer = admin.post("/api/v1/customers", Map.of("name", "Checklist Customer")).str("id");
        Map<String, Object> body = new HashMap<>();
        body.put("code", "CK-A");
        body.put("name", "Checklist project");
        body.put("customerId", customer);
        body.put("timeZone", "UTC");
        body.put("startDate", LocalDate.now(ZoneOffset.UTC).minusDays(30).toString());
        body.put("shifts", List.of(Map.of("code", "day", "name", "Day", "start", "06:00", "durationMinutes", 720)));
        project = admin.post("/api/v1/projects", body).str("id");
        admin.post("/api/v1/projects/" + project + "/access", Map.of("userId", managerId.toString()));
        admin.post("/api/v1/projects/" + project + "/access", Map.of("userId", otherId.toString()));
        rig = admin.post("/api/v1/assets", Map.of("typeId", rigType, "code", "RIG-CK", "attributes", Map.of("model", "LF90")))
                .str("id");
        admin.post("/api/v1/projects/" + project + "/equipment-assignments",
                Map.of("assetId", rig, "startDate", LocalDate.now(ZoneOffset.UTC).minusDays(20).toString()));
        manager = TestApi.loggedIn(port, "+380679920002", PASSWORD);
        other = TestApi.loggedIn(port, "+380679920003", PASSWORD);
    }

    @Test
    void checklistFlow() {
        String first = shift(10);
        Response fresh = admin.get(checklist(first));
        assertThat(fresh.str("status")).isEqualTo("new");
        List<?> items = (List<?>) fresh.at("items");
        assertThat(items).as("пункти з типу «Бурова установка»").hasSize(10);
        assertThat(fresh.at("canEdit")).isEqualTo(true);

        assertThat(admin.post(checklist(first), Map.of("answers", List.of())).field("items.fire_extinguishers"))
                .isEqualTo("required");
        assertThat(admin.post(checklist(first), Map.of("answers", answers(items, "hp_hoses", null))).field("items.hp_hoses"))
                .isEqualTo("comment_required");
        assertThat(other.post(checklist(first), Map.of("answers", answers(items, null, null))).status())
                .as("не бурильник зміни").isEqualTo(403);

        Response passed = admin.post(checklist(first), Map.of("answers", answers(items, null, null)));
        assertThat(passed.str("status")).isEqualTo("passed");
        assertThat(passed.str("serviceTaskId")).isNull();
        assertThat(admin.get("/api/v1/tasks/" + passed.str("checklistTaskId")).str("status")).isEqualTo("passed");
        String reportTask = admin.get("/api/v1/shifts/" + first + "/report").str("reportTaskId");
        assertThat(admin.get("/api/v1/tasks/" + reportTask).at("openBlockers")).as("рапорт уже можна подати").isEqualTo(0);
        assertThat(admin.post(checklist(first), Map.of("answers", answers(items, null, null))).str("error"))
                .isEqualTo("checklist_submitted");
        assertThat(admin.get("/api/v1/assets/" + rig).str("serviceLevel")).isEqualTo("ok");

        // Критичний пункт: «Сервіс» high і алерт менеджеру
        Response critical = admin.post(checklist(shift(9)), Map.of("answers", answers(items, "hp_hoses", "Hose cracked")));
        assertThat(critical.str("status")).isEqualTo("failed");
        Response service = admin.get("/api/v1/tasks/" + critical.str("serviceTaskId"));
        assertThat(service.str("typeCode")).isEqualTo("service");
        assertThat(service.str("assetId")).isEqualTo(rig);
        assertThat(service.str("attributes.criticality")).isEqualTo("high");
        assertThat(service.str("description")).contains("Hose cracked");
        Response alerts = manager.get("/api/v1/alerts");
        assertThat(alerts.at("unread")).isEqualTo(1);
        Map<?, ?> alert = (Map<?, ?>) ((List<?>) alerts.at("items")).getFirst();
        assertThat(alert.get("kind")).isEqualTo("critical_checklist");
        assertThat(alert.get("taskId")).isEqualTo(critical.str("serviceTaskId"));
        assertThat(admin.get("/api/v1/assets/" + rig).str("serviceLevel")).isEqualTo("high");

        // Некритичний: «Сервіс» medium без алерту; статус обладнання — за найвищою критичністю
        Response minor = admin.post(checklist(shift(8)), Map.of("answers", answers(items, "housekeeping", "Tools on the floor")));
        assertThat(admin.get("/api/v1/tasks/" + minor.str("serviceTaskId")).str("attributes.criticality")).isEqualTo("medium");
        assertThat(manager.get("/api/v1/alerts").at("unread")).isEqualTo(1);
        Response asset = admin.get("/api/v1/assets/" + rig);
        assertThat(asset.str("serviceLevel")).isEqualTo("high");
        assertThat(asset.at("openServiceTasks")).isEqualTo(2);
        Map<?, ?> boardAsset = (Map<?, ?>) ((List<?>) admin.get("/api/v1/projects/" + project + "/shift-board").at("assets")).getFirst();
        assertThat(boardAsset.get("serviceLevel")).isEqualTo("high");

        closeService(critical.str("serviceTaskId"));
        assertThat(admin.get("/api/v1/assets/" + rig).str("serviceLevel")).isEqualTo("medium");
        closeService(minor.str("serviceTaskId"));
        assertThat(admin.get("/api/v1/assets/" + rig).str("serviceLevel")).isEqualTo("ok");

        assertThat(manager.post("/api/v1/alerts/read-all", Map.of()).status()).isEqualTo(204);
        assertThat(manager.get("/api/v1/alerts").at("unread")).isEqualTo(0);
    }

    private void closeService(String id) {
        Response task = admin.get("/api/v1/tasks/" + id);
        assertThat(admin.post("/api/v1/tasks/" + id + "/status", Map.of("status", "done", "version", task.at("version"),
                "closingValues", Map.of("workDone", "Replaced", "criticality", "low"))).str("statusCategory")).isEqualTo("done");
    }

    private String shift(int daysAgo) {
        Response shift = admin.post("/api/v1/projects/" + project + "/shifts", Map.of("assetId", rig,
                "date", LocalDate.now(ZoneOffset.UTC).minusDays(daysAgo).toString(), "shiftCode", "day"));
        assertThat(shift.status()).isEqualTo(201);
        return shift.str("id");
    }

    /** Усі пункти pass, крім failKey (fail з коментарем). */
    private static List<Map<String, Object>> answers(List<?> items, String failKey, String comment) {
        List<Map<String, Object>> answers = new ArrayList<>();
        for (Object o : items) {
            String key = (String) ((Map<?, ?>) o).get("key");
            Map<String, Object> answer = new HashMap<>();
            answer.put("key", key);
            answer.put("result", key.equals(failKey) ? "fail" : "pass");
            if (key.equals(failKey)) {
                answer.put("comment", comment);
            }
            answers.add(answer);
        }
        return answers;
    }

    private static String checklist(String shiftId) {
        return "/api/v1/shifts/" + shiftId + "/checklist";
    }
}
