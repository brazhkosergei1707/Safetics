package com.safetics.monthly;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.YearMonth;
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
 * Крок 6б: місячний звіт — перевірені й включені неперевірені рапорти, коригування, версії,
 * надсилання, повернення й затвердження менеджером замовника, закриття місяця, коригувальний
 * запис у наступному місяці.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MonthlyReportTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "monthly-test-password";
    private static final int DURATION = 720;

    @Value("${local.server.port}")
    int port;

    @Autowired
    PasswordEncoder passwordEncoder;

    TestApi admin;
    TestApi hr;
    TestApi driller;
    TestApi client;
    TestApi clientManager;
    TestApi otherClient;
    String project;
    String rig;
    String hole;
    String holeTask;
    String drillerPerson;
    Map<String, String> codes = new HashMap<>();
    Map<String, String> items = new HashMap<>();
    final YearMonth month = YearMonth.now(ZoneOffset.UTC).minusMonths(1);

    @BeforeAll
    void setUp() {
        UUID company = addCompany("Monthly Co");
        String hash = passwordEncoder.encode(PASSWORD);
        addMember(company, "+380674300001", hash, "admin");
        addMember(company, "+380674300099", hash, "head_manager");
        addMember(company, "+380674300002", hash, "worker");
        admin = TestApi.loggedIn(port, "+380674300001", PASSWORD);
        hr = TestApi.loggedIn(port, "+380674300099", PASSWORD);
        admin.post("/api/v1/asset-types/install-template", Map.of("templateKey", "exploration-drilling"));
        for (Object c : admin.get("/api/v1/activity-codes").list()) {
            codes.put((String) ((Map<?, ?>) c).get("code"), (String) ((Map<?, ?>) c).get("id"));
        }
        for (Object i : admin.get("/api/v1/consumable-items").list()) {
            items.put((String) ((Map<?, ?>) i).get("code"), (String) ((Map<?, ?>) i).get("id"));
        }
        String rigType = id(admin.get("/api/v1/asset-types").list(), "drill_rig");
        String holeType = id(admin.get("/api/v1/service-types").list(), "drill_hole");
        String position = id(admin.get("/api/v1/positions").list(), "driller");
        String start = month.atDay(1).minusDays(5).toString();

        String customer = admin.post("/api/v1/customers", Map.of("name", "Monthly Customer")).str("id");
        project = admin.post("/api/v1/projects", projectBody("MR-A", customer, start)).str("id");
        String otherCustomer = admin.post("/api/v1/customers", Map.of("name", "Monthly Other")).str("id");
        admin.post("/api/v1/projects", projectBody("MR-B", otherCustomer, start));

        rig = admin.post("/api/v1/assets", Map.of("typeId", rigType, "code", "RIG-MR",
                "attributes", Map.of("model", "LF90", "engineHours", 1000))).str("id");
        admin.post("/api/v1/projects/" + project + "/equipment-assignments", Map.of("assetId", rig, "startDate", start));
        Response service = admin.post("/api/v1/projects/" + project + "/services", Map.of("typeId", holeType, "code", "MR-BH1",
                "attributes", Map.of("plannedDepth", 300)));
        hole = service.str("id");
        holeTask = service.str("mainTaskId");

        Map<String, Object> person = new HashMap<>();
        person.put("fullName", "Monthly Driller");
        person.put("phone", "+380674300002");
        person.put("positionId", position);
        drillerPerson = hr.post("/api/v1/personnel", person).str("id");
        hr.post("/api/v1/personnel/" + drillerPerson + "/login", Map.of());
        admin.post("/api/v1/projects/" + project + "/personnel-assignments", Map.of("personnelId", drillerPerson, "startDate", start));
        driller = TestApi.loggedIn(port, "+380674300002", PASSWORD);

        client = portal(customer, "Monthly Geologist", "+380674300004", "client_rep");
        clientManager = portal(customer, "Monthly Client Manager", "+380674300005", "client_manager");
        otherClient = portal(otherCustomer, "Monthly Other Rep", "+380674300006", "client_rep");
    }

    @Test
    void monthlyReportLifecycle() {
        String accepted = submitted(month.atDay(3));
        Object version = client.get("/api/v1/review/reports/" + accepted).at("report.version");
        assertThat(client.post("/api/v1/review/reports/" + accepted + "/accept", Map.of("version", version)).status()).isEqualTo(200);
        String unreviewed = submitted(month.atDay(5));
        String draft = shift(month.atDay(7));
        admin.put(report(draft), body(List.of(time(0, DURATION, "MAINT", null)), List.of(), 0));

        // Перегляд місяця: основна частина — перевірений, окремо — неперевірений, чернетка — лише лічильник
        Response view = admin.get(monthPath());
        assertThat(view.at("closed")).isEqualTo(false);
        assertThat(view.at("preview.acceptedCount")).isEqualTo(1);
        assertThat(view.at("preview.draftCount")).isEqualTo(1);
        assertThat((List<?>) view.at("preview.unreviewed")).hasSize(1);
        assertThat(num(view.at("preview.totals.meters"))).isEqualTo(35.5);
        assertThat(view.at("preview.totals.billableMinutes")).isEqualTo(600);

        // Включити неперевірений; коригування з коментарем
        Response included = admin.post(monthPath() + "/inclusions", Map.of("shiftId", unreviewed, "included", true));
        assertThat(num(included.at("preview.totals.meters"))).isEqualTo(71.0);
        assertThat(admin.post(monthPath() + "/adjustments", Map.of("kind", "meters", "amount", 2, "comment", " ")).field("comment"))
                .isEqualTo("required");
        Response adjusted = admin.post(monthPath() + "/adjustments",
                Map.of("kind", "meters", "amount", 2, "comment", "Re-measured BH1"));
        assertThat(num(adjusted.at("preview.totals.meters"))).isEqualTo(73.0);
        assertThat(num(adjusted.at("preview.totals.adjustmentMeters"))).isEqualTo(2.0);
        assertThat(driller.post(monthPath() + "/generate", Map.of()).status()).as("лише менеджер").isEqualTo(403);

        // Версії: нова генерація замінює чернетку
        Response v1 = admin.post(monthPath() + "/generate", Map.of());
        assertThat(v1.at("version")).isEqualTo(1);
        assertThat(v1.str("status")).isEqualTo("draft");
        assertThat(num(v1.at("content.totals.meters"))).isEqualTo(73.0);
        Response v2 = admin.post(monthPath() + "/generate", Map.of());
        assertThat(v2.at("version")).isEqualTo(2);
        assertThat(admin.get("/api/v1/monthly-reports/" + v1.str("id")).str("status")).isEqualTo("superseded");
        assertThat(client.get("/api/v1/review/monthly-reports/" + v2.str("id")).status()).as("чернетку замовник не бачить")
                .isEqualTo(404);

        // Надіслати; надісланий — знімок, нова генерація заборонена
        Response sent = admin.post("/api/v1/monthly-reports/" + v2.str("id") + "/send", Map.of());
        assertThat(sent.str("status")).isEqualTo("sent");
        assertThat(alertKinds(clientManager)).contains("monthly_sent");
        assertThat(admin.post(monthPath() + "/generate", Map.of()).str("error")).isEqualTo("report_sent");
        assertThat(client.get("/api/v1/review/monthly-reports").list()).extracting(r -> (Object) ((Map<?, ?>) r).get("id"))
                .contains(v2.str("id")).doesNotContain(v1.str("id"));
        assertThat(otherClient.get("/api/v1/review/monthly-reports").list()).isEmpty();

        // Повертає менеджер замовника з коментарем
        String reviewPath = "/api/v1/review/monthly-reports/" + v2.str("id");
        assertThat(client.post(reviewPath + "/approve", Map.of()).status()).as("затверджує менеджер замовника").isEqualTo(403);
        assertThat(clientManager.post(reviewPath + "/return", Map.of("comment", "")).field("comment")).isEqualTo("required");
        Response returned = clientManager.post(reviewPath + "/return", Map.of("comment", "BH1 meters do not match"));
        assertThat(returned.str("status")).isEqualTo("returned");
        assertThat(returned.str("decisionComment")).isEqualTo("BH1 meters do not match");
        assertThat(alertKinds(admin)).contains("monthly_returned");

        // Нова версія, надіслати, затвердити — місяць закрито
        Response v3 = admin.post(monthPath() + "/generate", Map.of());
        assertThat(v3.at("version")).isEqualTo(3);
        admin.post("/api/v1/monthly-reports/" + v3.str("id") + "/send", Map.of());
        Response approved = clientManager.post("/api/v1/review/monthly-reports/" + v3.str("id") + "/approve", Map.of());
        assertThat(approved.str("status")).isEqualTo("approved");
        assertThat(alertKinds(admin)).contains("monthly_approved");
        assertThat(admin.get(monthPath()).at("closed")).isEqualTo(true);
        assertThat(admin.post(monthPath() + "/generate", Map.of()).str("error")).isEqualTo("month_closed");
        assertThat(admin.post(monthPath() + "/adjustments", Map.of("kind", "hours", "amount", 1, "comment", "late")).str("error"))
                .isEqualTo("month_closed");
        Response draftReport = admin.get(report(draft));
        assertThat(draftReport.at("canEdit")).as("рапорт закритого місяця — лише читання").isEqualTo(false);
        assertThat(admin.put(report(draft), body(List.of(time(0, DURATION, "MAINT", null)), List.of(), draftReport.at("version")))
                .str("error")).isEqualTo("month_closed");

        // Коригувальний запис — у наступному місяці з посиланням на зміну закритого місяця
        String next = "/api/v1/projects/" + project + "/months/" + month.plusMonths(1);
        Response correction = admin.post(next + "/adjustments",
                Map.of("kind", "meters", "amount", -1, "comment", "Correction of BH1", "correctsShiftId", accepted));
        assertThat(correction.status()).isEqualTo(200);
        assertThat(((List<?>) correction.at("preview.adjustments")).stream().map(a -> (Object) ((Map<?, ?>) a).get("correctsShiftId")))
                .contains(accepted);
        String openShift = shift(LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1));
        assertThat(admin.post(next + "/adjustments", Map.of("kind", "meters", "amount", 1, "comment", "x", "correctsShiftId", openShift))
                .field("correctsShiftId")).isEqualTo("month_not_closed");
    }

    private String monthPath() {
        return "/api/v1/projects/" + project + "/months/" + month;
    }

    private static double num(Object value) {
        return ((Number) value).doubleValue();
    }

    // ---------------------------------------------------------------- допоміжне

    /** Зміна з бурильником (відповідальний) і поданим рапортом: 600 хв DRL + 120 хв MAINT, 35.5 м. */
    private String submitted(LocalDate date) {
        String shiftId = shift(date);
        Response draft = driller.put(report(shiftId), body(List.of(time(0, 600, "DRL", holeTask), time(600, DURATION, "MAINT", null)),
                List.of(interval(0, 35.5, 34)), 0));
        assertThat(draft.status()).isEqualTo(200);
        closeChecklist(shiftId);
        Response submitted = driller.post(report(shiftId) + "/submit", Map.of("version", draft.at("version")));
        assertThat(submitted.str("status")).isEqualTo("submitted");
        return shiftId;
    }

    private String shift(LocalDate date) {
        Response shift = admin.post("/api/v1/projects/" + project + "/shifts", Map.of("assetId", rig, "date", date.toString(),
                "shiftCode", "day", "crew", List.of(Map.of("personnelId", drillerPerson, "lead", true))));
        assertThat(shift.status()).isEqualTo(201);
        return shift.str("id");
    }

    private void closeChecklist(String shiftId) {
        Map<?, ?> checklist = ((List<?>) admin.get("/api/v1/shifts/" + shiftId).at("tasks")).stream()
                .<Map<?, ?>>map(t -> (Map<?, ?>) t).filter(t -> "shift_checklist".equals(t.get("typeCode")))
                .findFirst().orElseThrow();
        Response task = admin.get("/api/v1/tasks/" + checklist.get("id"));
        admin.post("/api/v1/tasks/" + checklist.get("id") + "/status", Map.of("status", "passed", "version", task.at("version")));
    }

    private TestApi portal(String customer, String name, String phone, String role) {
        String contact = admin.post("/api/v1/customers/" + customer + "/contacts", Map.of("fullName", name, "phone", phone))
                .str("id");
        admin.post("/api/v1/customer-contacts/" + contact + "/portal-access", Map.of("role", role, "password", "client-password"));
        return TestApi.loggedIn(port, phone, "client-password");
    }

    private Map<String, Object> projectBody(String code, String customer, String start) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("name", code);
        body.put("customerId", customer);
        body.put("timeZone", "UTC");
        body.put("startDate", start);
        body.put("shifts", List.of(Map.of("code", "day", "name", "Day", "start", "06:00", "durationMinutes", DURATION)));
        return body;
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

    private static List<Object> alertKinds(TestApi api) {
        return ((List<?>) api.get("/api/v1/alerts").at("items")).stream().map(a -> (Object) ((Map<?, ?>) a).get("kind")).toList();
    }

    private static String id(List<Object> list, String code) {
        return list.stream().<Map<?, ?>>map(x -> (Map<?, ?>) x).filter(x -> code.equals(x.get("code"))).findFirst().orElseThrow()
                .get("id").toString();
    }

    private static String report(String shiftId) {
        return "/api/v1/shifts/" + shiftId + "/report";
    }
}
