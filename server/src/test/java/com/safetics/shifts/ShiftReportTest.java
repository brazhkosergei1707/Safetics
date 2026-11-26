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
 * Змінний рапорт: чернетка → подання (після чек-листа), перевірки журналу часу, попередження,
 * заповнення з попередньої зміни, мотогодини в обладнання, права; зміни за період.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShiftReportTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "report-test-password";
    /** Одна 12-годинна зміна 06:00 */
    private static final int DURATION = 720;

    @Value("${local.server.port}")
    int port;

    @Autowired
    PasswordEncoder passwordEncoder;

    TestApi admin;
    /** Виконавців заводить головний менеджер (адмін не має personnel.manage) */
    TestApi hr;
    TestApi driller;
    TestApi other;
    String project;
    String rig;
    String hole;
    String holeTask;
    String drillerPerson;
    Map<String, String> codes = new HashMap<>();
    Map<String, String> items = new HashMap<>();

    @BeforeAll
    void setUp() {
        UUID company = addCompany("Reports Co");
        String hash = passwordEncoder.encode(PASSWORD);
        addMember(company, "+380679900001", hash, "admin");
        addMember(company, "+380679900099", hash, "head_manager");
        addMember(company, "+380679900002", hash, "worker");
        UUID otherId = addMember(company, "+380679900003", hash, "worker");
        admin = TestApi.loggedIn(port, "+380679900001", PASSWORD);
        hr = TestApi.loggedIn(port, "+380679900099", PASSWORD);
        admin.post("/api/v1/asset-types/install-template", Map.of("templateKey", "exploration-drilling"));
        for (Object c : admin.get("/api/v1/activity-codes").list()) {
            codes.put((String) ((Map<?, ?>) c).get("code"), (String) ((Map<?, ?>) c).get("id"));
        }
        for (Object i : admin.get("/api/v1/consumable-items").list()) {
            items.put((String) ((Map<?, ?>) i).get("code"), (String) ((Map<?, ?>) i).get("id"));
        }
        String rigType = admin.get("/api/v1/asset-types").list().stream().<Map<?, ?>>map(t -> (Map<?, ?>) t)
                .filter(t -> "drill_rig".equals(t.get("code"))).findFirst().orElseThrow().get("id").toString();
        String holeType = admin.get("/api/v1/service-types").list().stream().<Map<?, ?>>map(t -> (Map<?, ?>) t)
                .filter(t -> "drill_hole".equals(t.get("code"))).findFirst().orElseThrow().get("id").toString();
        String position = admin.get("/api/v1/positions").list().stream().<Map<?, ?>>map(p -> (Map<?, ?>) p)
                .filter(p -> "driller".equals(p.get("code"))).findFirst().orElseThrow().get("id").toString();

        String customer = admin.post("/api/v1/customers", Map.of("name", "Reports Customer")).str("id");
        Map<String, Object> body = new HashMap<>();
        body.put("code", "RP-A");
        body.put("name", "Reports project");
        body.put("customerId", customer);
        body.put("timeZone", "UTC");
        body.put("startDate", today().minusDays(40).toString());
        body.put("shifts", List.of(Map.of("code", "day", "name", "Day", "start", "06:00", "durationMinutes", DURATION)));
        project = admin.post("/api/v1/projects", body).str("id");
        admin.post("/api/v1/projects/" + project + "/access", Map.of("userId", otherId.toString()));

        rig = admin.post("/api/v1/assets", Map.of("typeId", rigType, "code", "RIG-RP",
                "attributes", Map.of("model", "LF90", "engineHours", 1000))).str("id");
        admin.post("/api/v1/projects/" + project + "/equipment-assignments",
                Map.of("assetId", rig, "startDate", today().minusDays(30).toString()));

        Response service = admin.post("/api/v1/projects/" + project + "/services", Map.of("typeId", holeType, "code", "RP-BH1",
                "attributes", Map.of("plannedDepth", 300)));
        hole = service.str("id");
        holeTask = service.str("mainTaskId");

        Map<String, Object> person = new HashMap<>();
        person.put("fullName", "Report Driller");
        person.put("phone", "+380679900002");
        person.put("positionId", position);
        drillerPerson = hr.post("/api/v1/personnel", person).str("id");
        hr.post("/api/v1/personnel/" + drillerPerson + "/login", Map.of());
        admin.post("/api/v1/projects/" + project + "/personnel-assignments",
                Map.of("personnelId", drillerPerson, "startDate", today().minusDays(30).toString()));
        driller = TestApi.loggedIn(port, "+380679900002", PASSWORD);
        other = TestApi.loggedIn(port, "+380679900003", PASSWORD);
    }

    @Test
    void draftSubmitAndChecks() {
        Response shift = shift(today().minusDays(8));
        String shiftId = shift.str("id");
        Response empty = admin.get(report(shiftId));
        assertThat(empty.str("status")).isEqualTo("new");
        assertThat(empty.at("canEdit")).isEqualTo(true);

        Map<String, Object> body = body(List.of(time(0, 600, "DRL", holeTask), time(600, DURATION, "MAINT", null)),
                List.of(interval(0, 35.5, 34)), 0);
        Response draft = admin.put(report(shiftId), body);
        assertThat(draft.status()).isEqualTo(200);
        assertThat(draft.str("status")).isEqualTo("draft");
        assertThat(draft.at("totals.billableMinutes")).isEqualTo(600);
        assertThat(draft.at("totals.nonBillableMinutes")).isEqualTo(120);
        assertThat(draft.str("totals.meters")).isEqualTo("35.5");
        assertThat(draft.str("totals.recovery")).isEqualTo("95.8");
        assertThat((List<?>) draft.at("errors")).isEmpty();
        assertThat((List<?>) draft.at("warnings")).isEmpty();
        String reportTask = draft.str("reportTaskId");
        assertThat(admin.get("/api/v1/tasks/" + reportTask).str("statusCategory")).as("блокує чек-лист").isEqualTo("new");

        int version = ((Number) draft.at("version")).intValue();
        assertThat(admin.post(report(shiftId) + "/submit", Map.of("version", version)).str("error"))
                .isEqualTo("checklist_required");
        closeChecklist(shiftId);
        Response submitted = admin.post(report(shiftId) + "/submit", Map.of("version", version));
        assertThat(submitted.str("status")).isEqualTo("submitted");
        assertThat(admin.get("/api/v1/tasks/" + reportTask).str("status")).isEqualTo("submitted");
        assertThat(admin.get("/api/v1/assets/" + rig).str("attributes.engineHours")).as("мотогодини в обладнання")
                .isEqualTo("1012.0");

        Map<String, Object> again = body(List.of(time(0, DURATION, "MAINT", null)), List.of(), submitted.at("version"));
        assertThat(admin.put(report(shiftId), again).str("error")).isEqualTo("report_submitted");
        assertThat(admin.put("/api/v1/shifts/" + shiftId + "/crew", Map.of("version", shift.at("version"), "crew", List.of()))
                .str("error")).isEqualTo("report_submitted");
        Response reopened = admin.post(report(shiftId) + "/reopen", Map.of("version", submitted.at("version")));
        assertThat(reopened.str("status")).isEqualTo("draft");
        assertThat(admin.get("/api/v1/tasks/" + reportTask).str("status")).isEqualTo("draft");

        // Наступна зміна: заповнення з попередньої, перевірки журналу, попередження
        String next = shift(today().minusDays(7)).str("id");
        Response fresh = admin.get(report(next));
        List<?> prefill = (List<?>) fresh.at("prefill");
        assertThat(prefill).hasSize(1);
        assertThat(((Map<?, ?>) prefill.getFirst()).get("depthFrom").toString()).isEqualTo("35.5");
        assertThat(((Map<?, ?>) prefill.getFirst()).get("taskId")).as("задача буріння з попередньої зміни").isEqualTo(holeTask);
        Map<?, ?> meter = (Map<?, ?>) ((List<?>) fresh.at("meters")).getFirst();
        assertThat(meter.get("startValue").toString()).isEqualTo("1012.0");

        Map<String, Object> gappy = body(List.of(time(0, 300, "DRL", holeTask), time(400, DURATION, "DRL", holeTask)),
                List.of(interval(40, 50, 12)), 0);
        Response saved = admin.put(report(next), gappy);
        assertThat(((List<?>) saved.at("errors")).stream().<Map<?, ?>>map(e -> (Map<?, ?>) e).map(e -> (Object) e.get("code")))
                .contains("time_gap", "time_total");
        assertThat(((List<?>) saved.at("warnings")).stream().<Map<?, ?>>map(e -> (Map<?, ?>) e).map(e -> (Object) e.get("code")))
                .contains("depth_gap", "core_over_100");
        closeChecklist(next);
        assertThat(admin.post(report(next) + "/submit", Map.of("version", saved.at("version"))).str("error"))
                .isEqualTo("report_incomplete");
    }

    @Test
    void validationAndRights() {
        String shiftId = shift(today().minusDays(5)).str("id");
        Map<String, Object> outside = body(List.of(time(0, DURATION + 60, "DRL", null)), List.of(), 0);
        assertThat(admin.put(report(shiftId), outside).field("times[0].endMinute")).isEqualTo("outside_shift");
        Map<String, Object> quantity = body(List.of(), List.of(), 0);
        quantity.put("quantities", List.of(Map.of("serviceId", hole, "quantity", 10)));
        assertThat(admin.put(report(shiftId), quantity).field("quantities[0].serviceId")).isEqualTo("use_intervals");

        Map<String, Object> ok = body(List.of(time(0, DURATION, "MAINT", null)), List.of(), 0);
        assertThat(other.put(report(shiftId), ok).status()).as("не в бригаді — зміни не видно").isEqualTo(404);
        // Бурильник зміни заповнює свій рапорт
        Response shift = admin.get("/api/v1/shifts/" + shiftId);
        admin.put("/api/v1/shifts/" + shiftId + "/crew", Map.of("version", shift.at("version"),
                "crew", List.of(Map.of("personnelId", drillerPerson, "lead", true))));
        assertThat(driller.put(report(shiftId), ok).status()).isEqualTo(200);
    }

    @Test
    void drillingRowCarriesInterval() {
        String shiftId = shift(today().minusDays(3)).str("id");
        // Буріння без задачі — помилка; задача не про свердловину — помилка; інтервал на не-бурінні — помилка
        Map<String, Object> noTask = body(List.of(time(0, DURATION, "DRL", null)), List.of(), 0);
        assertThat(admin.put(report(shiftId), noTask).field("times[0].taskId")).isEqualTo("required");
        String reportTask = (String) ((List<?>) admin.get("/api/v1/shifts/" + shiftId).at("tasks")).stream()
                .<Map<?, ?>>map(t -> (Map<?, ?>) t).filter(t -> "shift_report".equals(t.get("typeCode")))
                .findFirst().orElseThrow().get("id");
        Map<String, Object> notHole = body(List.of(time(0, DURATION, "DRL", reportTask)), List.of(), 0);
        assertThat(admin.put(report(shiftId), notHole).field("times[0].taskId")).isEqualTo("no_service");
        Map<String, Object> maint = time(0, DURATION, "MAINT", null);
        maint.put("interval", interval(0, 5, 5));
        Map<String, Object> wrongCode = body(List.of(maint), List.of(), 0);
        assertThat(admin.put(report(shiftId), wrongCode).field("times[0].interval")).isEqualTo("not_drilling");
        Map<String, Object> reversed = body(List.of(time(0, DURATION, "DRL", holeTask)), List.of(interval(10, 5, 5)), 0);
        assertThat(admin.put(report(shiftId), reversed).field("times[0].interval.depthTo")).isEqualTo("before_from");

        // Рядок буріння з інтервалом: свердловина — із задачі; другий рядок без інтервалу — попередження
        Map<String, Object> ok = body(List.of(time(0, 400, "DRL", holeTask), time(400, DURATION, "DRL", holeTask)),
                List.of(interval(100, 106, 6)), 0);
        Response saved = admin.put(report(shiftId), ok);
        assertThat(saved.status()).isEqualTo(200);
        assertThat(saved.str("times[0].interval.serviceId")).isEqualTo(hole);
        assertThat(saved.str("times[0].interval.serviceCode")).isEqualTo("RP-BH1");
        assertThat(saved.at("times[0].drilling")).isEqualTo(true);
        assertThat(saved.at("times[1].interval")).isNull();
        assertThat(saved.at("intervals[0].linked")).isEqualTo(true);
        assertThat(saved.str("totals.meters")).isEqualTo("6.0");
        assertThat(((List<?>) saved.at("warnings")).stream().<Map<?, ?>>map(e -> (Map<?, ?>) e).map(e -> (Object) e.get("code")))
                .contains("drilling_without_interval");
        // Задачі для журналу — зі свердловиною (веб підставляє задачу буріння)
        assertThat(((List<?>) saved.at("taskOptions")).stream().<Map<?, ?>>map(o -> (Map<?, ?>) o)
                .filter(o -> holeTask.equals(o.get("id"))).findFirst().orElseThrow().get("serviceId")).isEqualTo(hole);
    }

    @Test
    void generateShiftsForPeriod() {
        Map<String, Object> range = Map.of("from", today().minusDays(25).toString(), "to", today().minusDays(20).toString());
        Response generated = admin.post("/api/v1/projects/" + project + "/shifts/generate", range);
        assertThat(generated.at("created")).isEqualTo(6);
        assertThat(admin.post("/api/v1/projects/" + project + "/shifts/generate", range).at("created"))
                .as("наявні пропускаються").isEqualTo(0);
        assertThat(admin.post("/api/v1/projects/" + project + "/shifts/generate",
                Map.of("from", today().toString(), "to", today().plusDays(1).toString())).field("to")).isEqualTo("in_future");
        Response board = admin.get("/api/v1/projects/" + project + "/shift-board?from=" + today().minusDays(25) + "&to="
                + today().minusDays(20));
        List<?> shifts = (List<?>) board.at("shifts");
        assertThat(shifts).hasSize(6);
        assertThat((List<?>) ((Map<?, ?>) shifts.getFirst()).get("tasks")).as("задачі зміни створено").hasSize(2);
    }

    private Response shift(LocalDate date) {
        Response shift = admin.post("/api/v1/projects/" + project + "/shifts",
                Map.of("assetId", rig, "date", date.toString(), "shiftCode", "day"));
        assertThat(shift.status()).isEqualTo(201);
        return shift;
    }

    private void closeChecklist(String shiftId) {
        Map<?, ?> checklist = ((List<?>) admin.get("/api/v1/shifts/" + shiftId).at("tasks")).stream()
                .<Map<?, ?>>map(t -> (Map<?, ?>) t).filter(t -> "shift_checklist".equals(t.get("typeCode")))
                .findFirst().orElseThrow();
        Response task = admin.get("/api/v1/tasks/" + checklist.get("id"));
        assertThat(admin.post("/api/v1/tasks/" + checklist.get("id") + "/status",
                Map.of("status", "passed", "version", task.at("version"))).str("statusCategory")).isEqualTo("done");
    }

    private Map<String, Object> body(List<Map<String, Object>> times, List<Map<String, Object>> intervals, Object version) {
        Map<String, Object> body = new HashMap<>();
        body.put("times", withIntervals(times, intervals));
        body.put("quantities", List.of());
        body.put("consumables", List.of(Map.of("itemId", items.get("diesel"), "quantity", 120)));
        body.put("meters", List.of(Map.of("assetId", rig, "startValue", 1000, "endValue", 1012)));
        body.put("version", version);
        return body;
    }

    /** Інтервали — у рядки буріння (DRL) по черзі: рядок часу = один інтервал. */
    private List<Map<String, Object>> withIntervals(List<Map<String, Object>> times, List<Map<String, Object>> intervals) {
        List<Map<String, Object>> rows = new ArrayList<>();
        int next = 0;
        for (Map<String, Object> time : times) {
            Map<String, Object> row = new HashMap<>(time);
            if (next < intervals.size() && codes.get("DRL").equals(time.get("activityCodeId"))) {
                row.put("interval", intervals.get(next++));
            }
            rows.add(row);
        }
        return rows;
    }

    private Map<String, Object> time(int start, int end, String code, String task) {
        Map<String, Object> time = new HashMap<>();
        time.put("startMinute", start);
        time.put("endMinute", end);
        time.put("activityCodeId", codes.get(code));
        time.put("taskId", task);
        return time;
    }

    private Map<String, Object> interval(double from, double to, double core) {
        Map<String, Object> interval = new HashMap<>();
        interval.put("depthFrom", from);
        interval.put("depthTo", to);
        interval.put("coreSize", "HQ");
        interval.put("coreLength", core);
        return interval;
    }

    private static String report(String shiftId) {
        return "/api/v1/shifts/" + shiftId + "/report";
    }

    private static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }
}
