package com.safetics.projects;

import static com.safetics.projects.ProjectFixtures.project;
import static com.safetics.projects.ProjectFixtures.shift;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
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

/** Проєкти через HTTP: створення з графіком змін, перевірки графіка, версії графіка, закриття, дозволи. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProjectApiTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "projects-test-password";

    @Value("${local.server.port}")
    int port;

    @Autowired
    PasswordEncoder passwordEncoder;

    TestApi admin;
    TestApi worker;

    @BeforeAll
    void setUp() {
        UUID company = addCompany("Projects Co");
        String hash = passwordEncoder.encode(PASSWORD);
        addMember(company, "+380675000001", hash, "admin");
        addMember(company, "+380675000002", hash, "worker");
        admin = TestApi.loggedIn(port, "+380675000001", PASSWORD);
        worker = TestApi.loggedIn(port, "+380675000002", PASSWORD);
    }

    @Test
    void createsProjectWithShiftSchedule() {
        Response created = admin.post("/api/v1/projects", project("P-CREATE", null));
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.str("status")).isEqualTo("active");
        assertThat(created.str("country")).isEqualTo("BR");
        assertThat(created.str("timeZone")).isEqualTo("America/Belem");
        assertThat((List<?>) created.at("shifts")).hasSize(2);

        Response schedules = admin.get("/api/v1/projects/" + created.str("id") + "/shift-schedules");
        assertThat(schedules.list()).hasSize(1);
        assertThat(((Map<?, ?>) schedules.list().getFirst()).get("current")).isEqualTo(true);
        assertThat(((Map<?, ?>) schedules.list().getFirst()).get("editable")).isEqualTo(false);

        assertThat(admin.post("/api/v1/projects", project("p-create", null)).status())
                .as("код проєкту унікальний без урахування регістру").isEqualTo(409);
    }

    @Test
    void shiftScheduleRules() {
        // 3×8 і 1×12 — правильні графіки; нічна зміна через північ
        assertThat(admin.post("/api/v1/projects", withShifts("P-3X8",
                List.of(shift("a", "06:00", 480), shift("b", "14:00", 480), shift("c", "22:00", 480)))).status())
                .isEqualTo(201);
        assertThat(admin.post("/api/v1/projects", withShifts("P-1X12", List.of(shift("day", "07:00", 720)))).status())
                .isEqualTo(201);

        Response overlap = admin.post("/api/v1/projects", withShifts("P-BAD1",
                List.of(shift("day", "07:00", 720), shift("night", "18:00", 720))));
        assertThat(overlap.status()).isEqualTo(400);
        assertThat(overlap.field("shifts[1].start")).isEqualTo("overlap");

        // Нічна 20:00 на 13 год закінчується о 09:00 — заходить на денну наступної доби (з 08:00)
        Response acrossMidnight = admin.post("/api/v1/projects", withShifts("P-BAD2",
                List.of(shift("day", "08:00", 600), shift("night", "20:00", 780))));
        assertThat(acrossMidnight.field("shifts[0].start")).isEqualTo("overlap");

        Response longerThanDay = admin.post("/api/v1/projects", withShifts("P-BAD6",
                List.of(shift("day", "07:00", 720), shift("night", "19:00", 780))));
        assertThat(longerThanDay.field("shifts")).isEqualTo("exceeds_day");

        Response tooLong = admin.post("/api/v1/projects", withShifts("P-BAD3",
                List.of(shift("day", "07:00", 1500))));
        assertThat(tooLong.field("shifts[0].durationMinutes")).isEqualTo("too_large");

        Response badCode = admin.post("/api/v1/projects", withShifts("P-BAD4",
                List.of(shift("day", "07:00", 600), shift("day", "19:00", 600))));
        assertThat(badCode.field("shifts[1].code")).isEqualTo("duplicate");

        Response badTime = admin.post("/api/v1/projects", withShifts("P-BAD5", List.of(shift("day", "7am", 600))));
        assertThat(badTime.field("shifts[0].start")).isEqualTo("invalid_format");
    }

    @Test
    void validatesTimeZoneDatesAndCustomer() {
        Map<String, Object> badZone = project("P-TZ", null);
        badZone.put("timeZone", "Mars/Olympus");
        assertThat(admin.post("/api/v1/projects", badZone).field("timeZone")).isEqualTo("invalid_option");

        Map<String, Object> badDates = project("P-DATES", null);
        badDates.put("endDate", LocalDate.now().minusDays(60).toString());
        assertThat(admin.post("/api/v1/projects", badDates).field("endDate")).isEqualTo("before_start");

        Map<String, Object> badCustomer = project("P-CUST", UUID.randomUUID().toString());
        assertThat(admin.post("/api/v1/projects", badCustomer).field("customerId")).isEqualTo("not_found");
    }

    @Test
    void scheduleVersionsOnlyFromFutureDates() {
        String id = admin.post("/api/v1/projects", project("P-VERSIONS", null)).str("id");
        String path = "/api/v1/projects/" + id + "/shift-schedules";
        List<Map<String, Object>> threeShifts =
                List.of(shift("a", "06:00", 480), shift("b", "14:00", 480), shift("c", "22:00", 480));

        Response past = admin.post(path, Map.of("validFrom", LocalDate.now().minusDays(1).toString(), "shifts", threeShifts));
        assertThat(past.field("validFrom")).isEqualTo("not_in_future");

        String future = LocalDate.now().plusDays(10).toString();
        Response added = admin.post(path, Map.of("validFrom", future, "shifts", threeShifts));
        assertThat(added.status()).isEqualTo(200);
        assertThat(added.list()).hasSize(2);

        // Та сама дата — заміна, а не нова версія
        Response replaced = admin.post(path, Map.of("validFrom", future, "shifts", List.of(shift("day", "07:00", 720))));
        assertThat(replaced.list()).hasSize(2);
        Map<?, ?> futureVersion = (Map<?, ?>) replaced.list().get(1);
        assertThat(futureVersion.get("editable")).isEqualTo(true);
        assertThat((List<?>) futureVersion.get("shifts")).hasSize(1);

        // Сьогодні діє перша версія
        assertThat((List<?>) admin.get("/api/v1/projects/" + id).at("shifts")).hasSize(2);

        Map<?, ?> currentVersion = (Map<?, ?>) replaced.list().getFirst();
        assertThat(admin.delete(path + "/" + currentVersion.get("id")).status()).isEqualTo(409);
        assertThat(admin.delete(path + "/" + futureVersion.get("id")).list()).hasSize(1);
    }

    @Test
    void updateNeedsCurrentVersionAndCloseReopen() {
        Response created = admin.post("/api/v1/projects", project("P-UPDATE", null));
        String id = created.str("id");
        Map<String, Object> update = project("P-UPDATE", null);
        update.remove("shifts");
        update.put("name", "Renamed");
        update.put("version", 99);
        assertThat(admin.put("/api/v1/projects/" + id, update).status()).isEqualTo(409);
        update.put("version", 1);
        Response updated = admin.put("/api/v1/projects/" + id, update);
        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.str("name")).isEqualTo("Renamed");

        Response closed = admin.post("/api/v1/projects/" + id + "/close", Map.of());
        assertThat(closed.str("status")).isEqualTo("closed");
        assertThat(closed.str("endDate")).isNotNull();
        assertThat(admin.get("/api/v1/projects?status=active").list())
                .noneMatch(p -> id.equals(((Map<?, ?>) p).get("id")));
        assertThat(admin.post("/api/v1/projects/" + id + "/reopen", Map.of()).str("status")).isEqualTo("active");
    }

    @Test
    void workerCannotManageProjects() {
        assertThat(worker.post("/api/v1/projects", project("P-WORKER", null)).status()).isEqualTo(403);
        assertThat(worker.get("/api/v1/projects").list()).as("без доступу — порожній список").isEmpty();
    }

    private static Map<String, Object> withShifts(String code, List<Map<String, Object>> shifts) {
        Map<String, Object> body = project(code, null);
        body.put("shifts", shifts);
        return body;
    }
}
