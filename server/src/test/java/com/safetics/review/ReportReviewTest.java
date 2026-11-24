package com.safetics.review;

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
 * Крок 6а: перевірка змінного рапорту замовником — прийняти, повернути з коментарем, виправити
 * (погодження підрядником), події, задача рапорту, алерти, видимість лише свого замовника.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReportReviewTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "review-test-password";
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

    @BeforeAll
    void setUp() {
        UUID company = addCompany("Review Co");
        String hash = passwordEncoder.encode(PASSWORD);
        addMember(company, "+380674200001", hash, "admin");
        addMember(company, "+380674200099", hash, "head_manager");
        addMember(company, "+380674200002", hash, "worker");
        admin = TestApi.loggedIn(port, "+380674200001", PASSWORD);
        hr = TestApi.loggedIn(port, "+380674200099", PASSWORD);
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

        String customer = admin.post("/api/v1/customers", Map.of("name", "Review Customer")).str("id");
        project = admin.post("/api/v1/projects", projectBody("RV-A", customer)).str("id");
        String otherCustomer = admin.post("/api/v1/customers", Map.of("name", "Other Customer")).str("id");
        admin.post("/api/v1/projects", projectBody("RV-B", otherCustomer));

        rig = admin.post("/api/v1/assets", Map.of("typeId", rigType, "code", "RIG-RV",
                "attributes", Map.of("model", "LF90", "engineHours", 1000))).str("id");
        admin.post("/api/v1/projects/" + project + "/equipment-assignments",
                Map.of("assetId", rig, "startDate", today().minusDays(30).toString()));
        Response service = admin.post("/api/v1/projects/" + project + "/services", Map.of("typeId", holeType, "code", "RV-BH1",
                "attributes", Map.of("plannedDepth", 300)));
        hole = service.str("id");
        holeTask = service.str("mainTaskId");

        Map<String, Object> person = new HashMap<>();
        person.put("fullName", "Review Driller");
        person.put("phone", "+380674200002");
        person.put("positionId", position);
        drillerPerson = hr.post("/api/v1/personnel", person).str("id");
        hr.post("/api/v1/personnel/" + drillerPerson + "/login", Map.of());
        admin.post("/api/v1/projects/" + project + "/personnel-assignments",
                Map.of("personnelId", drillerPerson, "startDate", today().minusDays(30).toString()));
        driller = TestApi.loggedIn(port, "+380674200002", PASSWORD);

        client = portal(customer, "Review Geologist", "+380674200004", "client_rep");
        clientManager = portal(customer, "Review Client Manager", "+380674200005", "client_manager");
        otherClient = portal(otherCustomer, "Other Geologist", "+380674200006", "client_rep");
    }

    @Test
    void returnCorrectAgree() {
        String shiftId = submitted(today().minusDays(10));
        String reportTask = admin.get(report(shiftId)).str("reportTaskId");
        assertThat(((List<?>) driller.get(report(shiftId)).at("taskOptions")).stream().map(o -> (Object) ((Map<?, ?>) o).get("id")))
                .as("бурильник пише час на задачу свердловини, хоч і не бачить її в задачах").contains(holeTask);

        assertThat(driller.get("/api/v1/review/reports").status()).as("лише портал").isEqualTo(403);
        assertThat(client.get("/api/v1/review/reports?projectId=" + project).list())
                .extracting(r -> (Object) ((Map<?, ?>) r).get("shiftId")).contains(shiftId);
        Response view = client.get(review(shiftId));
        assertThat(view.str("report.status")).isEqualTo("submitted");
        assertThat(view.at("canReview")).isEqualTo(true);
        assertThat((List<?>) view.at("report.consumables")).as("витрати — не для замовника").isEmpty();
        assertThat((List<?>) view.at("report.meters")).as("мотогодини — не для замовника").isEmpty();
        assertThat((List<?>) view.at("crew")).hasSize(1);
        Object version = view.at("report.version");

        // Повернути: коментар обов'язковий; задача — «Повернуто», алерт бурильнику зміни
        assertThat(client.post(review(shiftId) + "/return", Map.of("version", version, "comment", " ")).field("comment"))
                .isEqualTo("required");
        Response returned = client.post(review(shiftId) + "/return", Map.of("version", version, "comment", "Check DRL hours"));
        assertThat(returned.str("report.status")).isEqualTo("returned");
        assertThat(returned.at("canReview")).isEqualTo(false);
        assertThat(admin.get("/api/v1/tasks/" + reportTask).str("status")).isEqualTo("returned");
        assertThat(alertKinds(driller)).contains("report_returned");

        // Підрядник править і подає знову
        Response fixed = driller.put(report(shiftId), body(List.of(time(0, 660, "DRL", holeTask), time(660, DURATION, "MAINT", null)),
                List.of(interval(0, 35, 34)), driller.get(report(shiftId)).at("version")));
        assertThat(fixed.str("status")).as("лишається повернутим до подання").isEqualTo("returned");
        Response resubmitted = driller.post(report(shiftId) + "/submit", Map.of("version", fixed.at("version")));
        assertThat(resubmitted.str("status")).isEqualTo("submitted");

        // Виправити: значення замовника окремо, рапорт не змінено
        Map<String, Object> correction = new HashMap<>();
        correction.put("version", client.get(review(shiftId)).at("report.version"));
        correction.put("comment", "DRL until 11:40");
        correction.put("times", List.of(time(0, 700, "DRL", holeTask), time(700, DURATION, "MAINT", null)));
        correction.put("intervals", List.of(interval(0, 36, 35)));
        correction.put("quantities", List.of());
        Response corrected = client.post(review(shiftId) + "/correct", correction);
        assertThat(corrected.str("report.status")).isEqualTo("corrected");
        Response pending = driller.get(report(shiftId));
        assertThat(pending.at("totals.billableMinutes")).as("рапорт ще не змінено").isEqualTo(660);
        assertThat(pending.at("correction.totals.billableMinutes")).isEqualTo(700);
        assertThat(pending.str("correction.comment")).isEqualTo("DRL until 11:40");
        assertThat(pending.at("canAgree")).isEqualTo(true);
        assertThat(alertKinds(driller)).contains("report_corrected");
        assertThat(client.post(review(shiftId) + "/accept", Map.of("version", corrected.at("report.version"))).str("error"))
                .as("чекає згоди підрядника").isEqualTo("report_not_submitted");

        // Підрядник погоджується: значення замовника — у рапорті, «Перевірено», задача закрита
        Response agreed = driller.post(report(shiftId) + "/agree", Map.of("version", pending.at("version")));
        assertThat(agreed.str("status")).isEqualTo("accepted");
        assertThat(agreed.at("totals.billableMinutes")).isEqualTo(700);
        assertThat(((Number) agreed.at("totals.meters")).doubleValue()).isEqualTo(36.0);
        assertThat((List<?>) agreed.at("consumables")).as("витрати підрядника лишились").hasSize(1);
        assertThat(agreed.at("acceptedAt")).isNotNull();
        assertThat(((List<?>) agreed.at("events")).stream().map(e -> (Object) ((Map<?, ?>) e).get("kind")))
                .containsExactly("submitted", "returned", "submitted", "corrected", "agreed");
        assertThat(admin.get("/api/v1/tasks/" + reportTask).str("statusCategory")).isEqualTo("done");
        assertThat(driller.post(report(shiftId) + "/reopen", Map.of("version", agreed.at("version"))).status())
                .as("перевірений повертає лише менеджер").isEqualTo(403);

        // Менеджер повертає перевірений у чернетку (переробка місяця) — задача знову в роботі
        Response reopened = admin.post(report(shiftId) + "/reopen", Map.of("version", agreed.at("version")));
        assertThat(reopened.str("status")).isEqualTo("draft");
        assertThat(admin.get("/api/v1/tasks/" + reportTask).str("statusCategory")).isEqualTo("active");
    }

    @Test
    void acceptAndVisibility() {
        String shiftId = submitted(today().minusDays(12));
        String reportTask = admin.get(report(shiftId)).str("reportTaskId");
        Object version = client.get(review(shiftId)).at("report.version");

        assertThat(clientManager.get(review(shiftId)).status()).as("менеджер замовника бачить").isEqualTo(200);
        assertThat(clientManager.post(review(shiftId) + "/accept", Map.of("version", version)).status())
                .as("перевіряє представник замовника").isEqualTo(403);
        assertThat(otherClient.get(review(shiftId)).status()).as("чужий замовник").isEqualTo(404);
        assertThat(otherClient.get("/api/v1/review/reports").list()).isEmpty();

        Response accepted = client.post(review(shiftId) + "/accept", Map.of("version", version));
        assertThat(accepted.str("report.status")).isEqualTo("accepted");
        assertThat(admin.get("/api/v1/tasks/" + reportTask).str("status")).isEqualTo("reviewed");
        assertThat(client.post(review(shiftId) + "/return", Map.of("version", accepted.at("report.version"), "comment", "late"))
                .str("error")).isEqualTo("report_not_submitted");
        assertThat(admin.put(report(shiftId), body(List.of(time(0, DURATION, "DRL", null)), List.of(),
                accepted.at("report.version"))).str("error")).as("перевірений не редагується").isEqualTo("report_submitted");

        // Чернетку замовник не бачить
        String draft = shift(today().minusDays(13));
        admin.put(report(draft), body(List.of(time(0, DURATION, "DRL", null)), List.of(), 0));
        assertThat(client.get(review(draft)).status()).isEqualTo(404);
        assertThat(client.get("/api/v1/review/reports").list()).extracting(r -> (Object) ((Map<?, ?>) r).get("shiftId"))
                .doesNotContain(draft);
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

    private Map<String, Object> projectBody(String code, String customer) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("name", code);
        body.put("customerId", customer);
        body.put("timeZone", "UTC");
        body.put("startDate", today().minusDays(40).toString());
        body.put("shifts", List.of(Map.of("code", "day", "name", "Day", "start", "06:00", "durationMinutes", DURATION)));
        return body;
    }

    private Map<String, Object> body(List<Map<String, Object>> times, List<Map<String, Object>> intervals, Object version) {
        Map<String, Object> body = new HashMap<>();
        body.put("times", times);
        body.put("intervals", intervals);
        body.put("quantities", List.of());
        body.put("consumables", List.of(Map.of("itemId", items.get("diesel"), "quantity", 120)));
        body.put("meters", List.of(Map.of("assetId", rig, "startValue", 1000, "endValue", 1012)));
        body.put("version", version);
        return body;
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
        interval.put("serviceId", hole);
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

    private static String review(String shiftId) {
        return "/api/v1/review/reports/" + shiftId;
    }

    private static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }
}
