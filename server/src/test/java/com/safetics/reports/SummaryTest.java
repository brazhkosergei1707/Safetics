package com.safetics.reports;

import static org.assertj.core.api.Assertions.assertThat;

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

/**
 * Зведені звіти: години оплачувані / неоплачувані, метри й діапазони глибини (інтервал ділиться між
 * діапазонами), періоди (місяць, півріччя), чернетки за перемикачем, завершені послуги обладнання.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SummaryTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "summary-test-password";
    private static final int DURATION = 720;

    @Value("${local.server.port}")
    int port;

    @Autowired
    PasswordEncoder passwordEncoder;

    TestApi admin;
    String project;
    String rig;
    String holeType;
    Map<String, String> codes = new HashMap<>();
    Map<String, String> items = new HashMap<>();

    @BeforeAll
    void setUp() {
        UUID company = addCompany("Summary Co");
        addMember(company, "+380679910001", passwordEncoder.encode(PASSWORD), "admin");
        admin = TestApi.loggedIn(port, "+380679910001", PASSWORD);
        admin.post("/api/v1/asset-types/install-template", Map.of("templateKey", "exploration-drilling"));
        for (Object c : admin.get("/api/v1/activity-codes").list()) {
            codes.put((String) ((Map<?, ?>) c).get("code"), (String) ((Map<?, ?>) c).get("id"));
        }
        for (Object i : admin.get("/api/v1/consumable-items").list()) {
            items.put((String) ((Map<?, ?>) i).get("code"), (String) ((Map<?, ?>) i).get("id"));
        }
        String rigType = admin.get("/api/v1/asset-types").list().stream().<Map<?, ?>>map(t -> (Map<?, ?>) t)
                .filter(t -> "drill_rig".equals(t.get("code"))).findFirst().orElseThrow().get("id").toString();
        holeType = admin.get("/api/v1/service-types").list().stream().<Map<?, ?>>map(t -> (Map<?, ?>) t)
                .filter(t -> "drill_hole".equals(t.get("code"))).findFirst().orElseThrow().get("id").toString();
        String customer = admin.post("/api/v1/customers", Map.of("name", "Summary Customer")).str("id");
        Map<String, Object> body = new HashMap<>();
        body.put("code", "SM-A");
        body.put("name", "Summary project");
        body.put("customerId", customer);
        body.put("timeZone", "UTC");
        body.put("startDate", "2025-01-01");
        body.put("shifts", List.of(Map.of("code", "day", "name", "Day", "start", "06:00", "durationMinutes", DURATION)));
        project = admin.post("/api/v1/projects", body).str("id");
        rig = admin.post("/api/v1/assets", Map.of("typeId", rigType, "code", "RIG-SM", "attributes", Map.of("model", "LF90")))
                .str("id");
        admin.post("/api/v1/projects/" + project + "/equipment-assignments", Map.of("assetId", rig, "startDate", "2025-01-01"));
    }

    @Test
    void hoursMetersBandsAndPeriods() {
        Response hole = hole("SM-BH1");
        String task = hole.str("mainTaskId");
        report("2025-05-31", List.of(time(0, 600, "DRL", task), time(600, DURATION, "MAINT", null)),
                interval(hole.str("id"), 80, 130, 45), true);
        report("2025-06-01", List.of(time(0, DURATION, "DRL", task)), interval(hole.str("id"), 130, 150, 20), false);
        report("2025-07-15", List.of(time(0, DURATION, "DRL", task)), interval(hole.str("id"), 150, 210, 60), true);

        Response month = summary("2025-05-01", "2025-07-31", "month", 100, false);
        List<Map<?, ?>> rows = rows(month);
        assertThat(rows).hasSize(3);
        Map<?, ?> may = rows.get(0);
        assertThat(may.get("periodStart")).isEqualTo("2025-05-01");
        assertThat(may.get("periodEnd")).isEqualTo("2025-05-31");
        assertThat(may.get("billableMinutes")).isEqualTo(600);
        assertThat(may.get("nonBillableMinutes")).isEqualTo(120);
        assertThat(num(may.get("meters"))).isEqualTo(50.0);
        assertThat(num(may.get("recovery"))).isEqualTo(90.0);
        assertThat(bands(may)).containsExactly(Map.entry(0.0, 20.0), Map.entry(100.0, 30.0));
        assertThat(((List<?>) may.get("consumables"))).hasSize(1);
        Map<?, ?> june = rows.get(1);
        assertThat(june.get("reportsDraft")).isEqualTo(1);
        assertThat(num(june.get("meters"))).as("чернетки не враховуються").isEqualTo(0.0);
        assertThat(bands(rows.get(2))).containsExactly(Map.entry(100.0, 50.0), Map.entry(200.0, 10.0));
        Map<?, ?> total = (Map<?, ?>) month.at("total");
        assertThat(total.get("shifts")).isEqualTo(3);
        assertThat(num(total.get("meters"))).isEqualTo(110.0);
        assertThat(total.get("billableMinutes")).isEqualTo(600 + DURATION);

        assertThat(num(((Map<?, ?>) summary("2025-05-01", "2025-07-31", "month", 100, true).at("total")).get("meters")))
                .as("з чернетками").isEqualTo(130.0);
        List<Map<?, ?>> halves = rows(summary("2025-01-01", "2025-12-31", "half_year", 100, false));
        assertThat(halves).hasSize(2);
        assertThat(halves.get(0).get("periodStart")).isEqualTo("2025-01-01");
        assertThat(halves.get(0).get("periodEnd")).isEqualTo("2025-06-30");
        assertThat(num(halves.get(0).get("meters"))).isEqualTo(50.0);
        assertThat(halves.get(1).get("periodStart")).isEqualTo("2025-07-01");
        assertThat(bands(rows(summary("2025-05-01", "2025-05-31", "month", 50, false)).getFirst()))
                .containsExactly(Map.entry(50.0, 20.0), Map.entry(100.0, 30.0));
        assertThat(admin.get("/api/v1/reports/summary?from=2025-01-01&to=2025-02-01&period=week").field("period"))
                .isEqualTo("invalid_option");
    }

    @Test
    void completedServicesCountForEquipment() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Response hole = hole("SM-BH2");
        report(today.minusDays(1).toString(), List.of(time(0, DURATION, "DRL", hole.str("mainTaskId"))),
                interval(hole.str("id"), 0, 12, 11), true);
        admin.post("/api/v1/services/" + hole.str("id") + "/start", Map.of());
        assertThat(admin.post("/api/v1/services/" + hole.str("id") + "/complete",
                Map.of("closingValues", Map.of("actualDepth", 12, "completionReason", "target_depth"))).str("status"))
                .isEqualTo("completed");
        Response summary = summary(today.minusDays(40).toString(), today.toString(), "month", 100, false);
        String thisMonth = today.withDayOfMonth(1).toString();
        Map<?, ?> row = rows(summary).stream().filter(r -> thisMonth.equals(r.get("periodStart"))).findFirst().orElseThrow();
        assertThat(row.get("completedServices")).isEqualTo(1);
    }

    private Response summary(String from, String to, String period, int step, boolean drafts) {
        Response response = admin.get("/api/v1/reports/summary?projectId=" + project + "&from=" + from + "&to=" + to
                + "&period=" + period + "&depthStep=" + step + "&includeDrafts=" + drafts);
        assertThat(response.status()).isEqualTo(200);
        return response;
    }

    private static List<Map<?, ?>> rows(Response summary) {
        return ((List<?>) summary.at("rows")).stream().<Map<?, ?>>map(r -> (Map<?, ?>) r).toList();
    }

    private static List<Map.Entry<Double, Double>> bands(Map<?, ?> row) {
        return ((List<?>) row.get("depthBands")).stream().<Map<?, ?>>map(b -> (Map<?, ?>) b)
                .map(b -> Map.entry(num(b.get("from")), num(b.get("meters")))).toList();
    }

    private static double num(Object value) {
        return value == null ? Double.NaN : ((Number) value).doubleValue();
    }

    private Response hole(String code) {
        Response hole = admin.post("/api/v1/projects/" + project + "/services",
                Map.of("typeId", holeType, "code", code, "attributes", Map.of("plannedDepth", 300)));
        assertThat(hole.status()).isEqualTo(201);
        return hole;
    }

    /** Зміна на дату, рапорт (журнал, інтервал, дизель); submit — після закриття чек-листа. */
    private void report(String date, List<Map<String, Object>> times, Map<String, Object> interval, boolean submit) {
        Response shift = admin.post("/api/v1/projects/" + project + "/shifts",
                Map.of("assetId", rig, "date", date, "shiftCode", "day"));
        assertThat(shift.status()).isEqualTo(201);
        String shiftId = shift.str("id");
        Map<String, Object> body = new HashMap<>();
        body.put("times", times);
        body.put("intervals", List.of(interval));
        body.put("quantities", List.of());
        body.put("consumables", List.of(Map.of("itemId", items.get("diesel"), "quantity", 100)));
        body.put("meters", List.of());
        body.put("version", 0);
        Response saved = admin.put("/api/v1/shifts/" + shiftId + "/report", body);
        assertThat(saved.status()).isEqualTo(200);
        if (!submit) {
            return;
        }
        Map<?, ?> checklist = ((List<?>) admin.get("/api/v1/shifts/" + shiftId).at("tasks")).stream()
                .<Map<?, ?>>map(t -> (Map<?, ?>) t).filter(t -> "shift_checklist".equals(t.get("typeCode")))
                .findFirst().orElseThrow();
        Response task = admin.get("/api/v1/tasks/" + checklist.get("id"));
        admin.post("/api/v1/tasks/" + checklist.get("id") + "/status", Map.of("status", "passed", "version", task.at("version")));
        assertThat(admin.post("/api/v1/shifts/" + shiftId + "/report/submit", Map.of("version", saved.at("version")))
                .str("status")).isEqualTo("submitted");
    }

    private Map<String, Object> time(int start, int end, String code, String task) {
        Map<String, Object> time = new HashMap<>();
        time.put("startMinute", start);
        time.put("endMinute", end);
        time.put("activityCodeId", codes.get(code));
        time.put("taskId", task);
        return time;
    }

    private static Map<String, Object> interval(String service, double from, double to, double core) {
        Map<String, Object> interval = new HashMap<>();
        interval.put("serviceId", service);
        interval.put("depthFrom", from);
        interval.put("depthTo", to);
        interval.put("coreSize", "HQ");
        interval.put("coreLength", core);
        return interval;
    }
}
