package com.safetics.shifts;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
import com.safetics.core.tenancy.TenantContext;

/**
 * Зміни: задача «Бурова на проєкті» при призначенні основного обладнання, планувальник створює
 * зміну й змінний рапорт, бригада (заповнення з попередньої зміни, бурильник зміни отримує рапорт),
 * перевірки бригади, права; довідник кодів операцій.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShiftApiTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "shift-test-password";

    @Value("${local.server.port}")
    int port;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    WorkShiftService shifts;

    UUID company;
    TestApi admin;
    /** Виконавців заводить головний менеджер (адмін не має personnel.manage) */
    TestApi hr;
    TestApi worker;
    TestApi client;
    UUID driverUserId;
    String project;
    String rig;
    String rigType;
    String rigAssignment;
    String driller;
    String helper;
    String outsider;
    Map<String, String> positions = new HashMap<>();

    @BeforeAll
    void setUp() {
        company = addCompany("Shifts Co");
        String hash = passwordEncoder.encode(PASSWORD);
        addMember(company, "+380679700001", hash, "admin");
        addMember(company, "+380679700099", hash, "head_manager");
        driverUserId = addMember(company, "+380679700002", hash, "worker");
        admin = TestApi.loggedIn(port, "+380679700001", PASSWORD);
        hr = TestApi.loggedIn(port, "+380679700099", PASSWORD);
        admin.post("/api/v1/asset-types/install-template", Map.of("templateKey", "exploration-drilling"));
        for (Object p : admin.get("/api/v1/positions").list()) {
            positions.put((String) ((Map<?, ?>) p).get("code"), (String) ((Map<?, ?>) p).get("id"));
        }
        rigType = admin.get("/api/v1/asset-types").list().stream().<Map<?, ?>>map(t -> (Map<?, ?>) t)
                .filter(t -> "drill_rig".equals(t.get("code"))).findFirst().orElseThrow().get("id").toString();

        String customer = admin.post("/api/v1/customers", Map.of("name", "Shifts Customer")).str("id");
        Map<String, Object> body = new HashMap<>();
        body.put("code", "SH-A");
        body.put("name", "Shifts project");
        body.put("customerId", customer);
        body.put("timeZone", "UTC");
        body.put("startDate", LocalDate.now(ZoneOffset.UTC).minusDays(10).toString());
        // Одна зміна на всю добу: будь-який момент сьогодні — усередині зміни
        body.put("shifts", List.of(Map.of("code", "day", "name", "Day", "start", "00:00", "durationMinutes", 1440)));
        project = admin.post("/api/v1/projects", body).str("id");

        rig = admin.post("/api/v1/assets", Map.of("typeId", rigType, "code", "RIG-SH", "attributes", Map.of("model", "LF90")))
                .str("id");
        rigAssignment = admin.post("/api/v1/projects/" + project + "/equipment-assignments",
                Map.of("assetId", rig, "startDate", LocalDate.now(ZoneOffset.UTC).minusDays(5).toString())).str("id");

        driller = person("Shift Driller", "+380679700002", "driller");
        hr.post("/api/v1/personnel/" + driller + "/login", Map.of());
        helper = person("Shift Helper", null, "driller_helper");
        outsider = hr.post("/api/v1/personnel", Map.of("fullName", "Not On Project", "positionId", positions.get("driller")))
                .str("id");
        for (String id : List.of(driller, helper)) {
            admin.post("/api/v1/projects/" + project + "/personnel-assignments",
                    Map.of("personnelId", id, "startDate", LocalDate.now(ZoneOffset.UTC).minusDays(5).toString()));
        }
        worker = TestApi.loggedIn(port, "+380679700002", PASSWORD);

        Map<String, Object> contact = new HashMap<>();
        contact.put("fullName", "Shift Geologist");
        contact.put("phone", "+380679700004");
        String contactId = admin.post("/api/v1/customers/" + customer + "/contacts", contact).str("id");
        admin.post("/api/v1/customer-contacts/" + contactId + "/portal-access",
                Map.of("role", "client_rep", "password", "client-password"));
        client = TestApi.loggedIn(port, "+380679700004", "client-password");
    }

    @Test
    void shiftLifecycle() {
        // Призначення основного обладнання → «Бурова на проєкті»
        List<Map<?, ?>> rigTasks = tasksOfType("rig_on_project").stream().filter(t -> rig.equals(t.get("assetId"))).toList();
        assertThat(rigTasks).hasSize(1);
        assertThat(rigTasks.getFirst().get("statusCategory")).isEqualTo("active");
        assertThat(rigTasks.getFirst().get("assetId")).isEqualTo(rig);

        // Планувальник: зміна, що йде зараз, і змінний рапорт
        assertThat(runScheduler()).as("бурова RIG-SH (+ RIG-SH3, RIG-SH2 з інших тестів)").isBetween(1, 3);
        assertThat(runScheduler()).as("повторний прохід нічого не дублює").isZero();
        Response board = admin.get("/api/v1/projects/" + project + "/shift-board");
        List<Map<?, ?>> boardShifts = ((List<?>) board.at("shifts")).stream().<Map<?, ?>>map(s -> (Map<?, ?>) s)
                .filter(s -> LocalDate.now(ZoneOffset.UTC).toString().equals(s.get("date")) && rig.equals(s.get("assetId")))
                .toList();
        assertThat(boardShifts).hasSize(1);
        Map<?, ?> today = boardShifts.getFirst();
        assertThat(today.get("state")).isEqualTo("running");
        assertThat((List<?>) today.get("crew")).as("попередньої зміни немає").isEmpty();
        List<?> shiftTasks = (List<?>) today.get("tasks");
        assertThat(shiftTasks).as("чек-лист і рапорт").hasSize(2);
        Map<?, ?> report = shiftTasks.stream().<Map<?, ?>>map(x -> (Map<?, ?>) x)
                .filter(x -> "shift_report".equals(x.get("typeCode"))).findFirst().orElseThrow();
        assertThat(shiftTasks.stream().<Map<?, ?>>map(x -> (Map<?, ?>) x).map(x -> (Object) x.get("typeCode")))
                .contains("shift_checklist");
        assertThat(report.get("title").toString()).startsWith("Shift report: RIG-SH · ");
        String shiftId = today.get("id").toString();

        // Бригада: бурильник зміни отримує рапорт
        Response crew = admin.put("/api/v1/shifts/" + shiftId + "/crew", Map.of("version", today.get("version"), "crew", List.of(
                Map.of("personnelId", driller, "lead", true),
                Map.of("personnelId", helper, "minutes", 600))));
        assertThat(crew.status()).isEqualTo(200);
        assertThat((List<?>) crew.at("crew")).hasSize(2);
        assertThat(admin.get("/api/v1/tasks/" + report.get("id")).str("assigneeId")).isEqualTo(driverUserId.toString());

        // Наступна зміна (вручну, завтра) — бригада з попередньої, рапорт одразу бурильнику
        Map<String, Object> next = Map.of("assetId", rig, "date", LocalDate.now(ZoneOffset.UTC).plusDays(1).toString(),
                "shiftCode", "day");
        Response tomorrow = admin.post("/api/v1/projects/" + project + "/shifts", next);
        assertThat(tomorrow.status()).isEqualTo(201);
        assertThat(tomorrow.str("state")).isEqualTo("upcoming");
        assertThat((List<?>) tomorrow.at("crew")).hasSize(2);
        String tomorrowReport = ((List<?>) tomorrow.at("tasks")).stream().<Map<?, ?>>map(x -> (Map<?, ?>) x)
                .filter(x -> "shift_report".equals(x.get("typeCode"))).findFirst().orElseThrow().get("id").toString();
        assertThat(admin.get("/api/v1/tasks/" + tomorrowReport).str("assigneeId")).isEqualTo(driverUserId.toString());
        assertThat(admin.post("/api/v1/projects/" + project + "/shifts", next).str("error")).isEqualTo("shift_exists");
    }

    @Test
    void crewRulesAndRights() {
        Map<String, Object> body = Map.of("assetId", rig, "date", LocalDate.now(ZoneOffset.UTC).minusDays(2).toString(),
                "shiftCode", "day");
        Response shift = admin.post("/api/v1/projects/" + project + "/shifts", body);
        assertThat(shift.status()).isEqualTo(201);
        String path = "/api/v1/shifts/" + shift.str("id") + "/crew";
        Object version = shift.at("version");

        assertThat(admin.put(path, Map.of("version", version, "crew", List.of(Map.of("personnelId", outsider))))
                .field("crew[0].personnelId")).isEqualTo("not_on_project");
        assertThat(admin.put(path, Map.of("version", version, "crew", List.of(
                Map.of("personnelId", driller, "lead", true), Map.of("personnelId", helper, "lead", true))))
                .field("crew")).isEqualTo("one_lead");
        assertThat(admin.put(path, Map.of("version", version, "crew", List.of(Map.of("personnelId", helper, "minutes", 2000))))
                .field("crew[0].minutes")).isEqualTo("too_large");
        assertThat(admin.put(path, Map.of("version", version, "crew", List.of(
                Map.of("personnelId", driller), Map.of("personnelId", helper))))
                .field("crew")).as("відповідальний обов'язковий").isEqualTo("lead_required");
        assertThat(admin.put(path, Map.of("version", version, "crew", List.of(Map.of("personnelId", helper, "lead", true))))
                .field("crew[0].lead")).as("відповідальний — лише з входом").isEqualTo("no_login");
        assertThat(worker.put(path, Map.of("version", version, "crew", List.of())).status())
                .as("не в бригаді — зміни не видно").isEqualTo(404);
        assertThat(client.get("/api/v1/projects/" + project + "/shift-board").status()).as("портал змін не бачить")
                .isEqualTo(403);
        assertThat(admin.post("/api/v1/projects/" + project + "/shifts",
                Map.of("assetId", rig, "date", LocalDate.now(ZoneOffset.UTC).toString(), "shiftCode", "night"))
                .field("shiftCode")).isEqualTo("not_found");
    }

    @Test
    void manualShiftWithCrewAssignsTasksToLead() {
        // Окрема бурова: бригада цієї зміни не має потрапити в зміни інших тестів (заповнення з попередньої)
        String rig2 = admin.post("/api/v1/assets", Map.of("typeId", rigType, "code", "RIG-SH3", "attributes", Map.of("model", "LF90")))
                .str("id");
        admin.post("/api/v1/projects/" + project + "/equipment-assignments",
                Map.of("assetId", rig2, "startDate", LocalDate.now(ZoneOffset.UTC).minusDays(5).toString()));
        Map<String, Object> body = Map.of("assetId", rig2, "date", LocalDate.now(ZoneOffset.UTC).minusDays(3).toString(),
                "shiftCode", "day", "crew", List.of(Map.of("personnelId", helper), Map.of("personnelId", driller, "lead", true)));
        Response shift = admin.post("/api/v1/projects/" + project + "/shifts", body);
        assertThat(shift.status()).isEqualTo(201);
        assertThat((List<?>) shift.at("crew")).hasSize(2);
        List<Map<?, ?>> shiftTasks = ((List<?>) shift.at("tasks")).stream().<Map<?, ?>>map(x -> (Map<?, ?>) x).toList();
        assertThat(shiftTasks).hasSize(2);
        for (Map<?, ?> task : shiftTasks) {
            assertThat(admin.get("/api/v1/tasks/" + task.get("id")).str("assigneeId")).isEqualTo(driverUserId.toString());
        }
        assertThat(admin.post("/api/v1/projects/" + project + "/shifts", Map.of("assetId", rig2,
                "date", LocalDate.now(ZoneOffset.UTC).minusDays(4).toString(), "shiftCode", "day",
                "crew", List.of(Map.of("personnelId", helper)))).field("crew")).isEqualTo("lead_required");
    }

    @Test
    void activityCodesFromTemplateAndOwn() {
        List<Map<?, ?>> codes = admin.get("/api/v1/activity-codes").list().stream().<Map<?, ?>>map(c -> (Map<?, ?>) c).toList();
        assertThat(codes).extracting(c -> (Object) c.get("code")).contains("DRL", "TRIP_BIT", "WAIT_CLIENT");
        assertThat(codes.stream().filter(c -> "MAINT".equals(c.get("code"))).findFirst().orElseThrow().get("billable"))
                .isEqualTo(false);

        Response own = admin.post("/api/v1/activity-codes",
                Map.of("code", "WAIT_PERMIT", "name", Map.of("en", "Waiting for permit"), "category", "client_standby"));
        assertThat(own.status()).isEqualTo(201);
        assertThat(own.at("billable")).as("за категорією").isEqualTo(true);
        assertThat(admin.post("/api/v1/activity-codes",
                Map.of("code", "WAIT_PERMIT", "name", Map.of("en", "Again"), "category", "other")).field("code"))
                .isEqualTo("duplicate");
        assertThat(worker.post("/api/v1/activity-codes",
                Map.of("code", "X1", "name", Map.of("en", "X"), "category", "other")).status()).isEqualTo(403);
        assertThat(admin.get("/api/v1/consumable-items").list()).isNotEmpty();
    }

    @Test
    void deletingAssignmentCancelsRigTask() {
        String rigType = admin.get("/api/v1/asset-types").list().stream().<Map<?, ?>>map(t -> (Map<?, ?>) t)
                .filter(t -> "drill_rig".equals(t.get("code"))).findFirst().orElseThrow().get("id").toString();
        String second = admin.post("/api/v1/assets", Map.of("typeId", rigType, "code", "RIG-SH2", "attributes", Map.of("model", "LF90")))
                .str("id");
        String assignment = admin.post("/api/v1/projects/" + project + "/equipment-assignments",
                Map.of("assetId", second, "startDate", LocalDate.now(ZoneOffset.UTC).toString())).str("id");
        Map<?, ?> task = tasksOfType("rig_on_project").stream().filter(t -> second.equals(t.get("assetId"))).findFirst().orElseThrow();
        assertThat(admin.delete("/api/v1/projects/" + project + "/equipment-assignments/" + assignment).status()).isEqualTo(204);
        assertThat(admin.get("/api/v1/tasks/" + task.get("id")).str("statusCategory")).isEqualTo("cancelled");
    }

    private int runScheduler() {
        return TenantContext.callAs(company, null, () -> shifts.runDue(Instant.now()));
    }

    private List<Map<?, ?>> tasksOfType(String code) {
        return admin.get("/api/v1/projects/" + project + "/tasks").list().stream().<Map<?, ?>>map(t -> (Map<?, ?>) t)
                .filter(t -> code.equals(t.get("typeCode"))).toList();
    }

    private String person(String name, String phone, String position) {
        Map<String, Object> body = new HashMap<>();
        body.put("fullName", name);
        body.put("phone", phone);
        body.put("positionId", positions.get(position));
        return hr.post("/api/v1/personnel", body).str("id");
    }
}
