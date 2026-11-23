package com.safetics.projects;

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
 * Призначення на проєкт: без перекриттів у часі (і з проєктом, якого не видно), у межах дат
 * проєкту, лише обладнання верхнього рівня; доступ до проєкту для людини з входом; супервайзер
 * додає людей без входу лише з дозволеними посадами.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AssignmentApiTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "assign-test-password";

    @Value("${local.server.port}")
    int port;

    @Autowired
    PasswordEncoder passwordEncoder;

    TestApi admin;
    /** Виконавців заводить головний менеджер (адмін не має personnel.manage) */
    TestApi hr;
    TestApi supervisor;
    TestApi otherWorker;
    Map<String, String> positionIds = new HashMap<>();
    Map<String, String> typeIds = new HashMap<>();
    String projectA;
    String projectB;
    LocalDate start = LocalDate.now().minusDays(30);

    @BeforeAll
    void setUp() {
        UUID company = addCompany("Assignments Co");
        String hash = passwordEncoder.encode(PASSWORD);
        addMember(company, "+380678000001", hash, "admin");
        addMember(company, "+380678000099", hash, "head_manager");
        addMember(company, "+380678000002", hash, "worker");
        addMember(company, "+380678000003", hash, "worker");
        admin = TestApi.loggedIn(port, "+380678000001", PASSWORD);
        hr = TestApi.loggedIn(port, "+380678000099", PASSWORD);
        supervisor = TestApi.loggedIn(port, "+380678000002", PASSWORD);
        otherWorker = TestApi.loggedIn(port, "+380678000003", PASSWORD);

        admin.post("/api/v1/asset-types/install-template", Map.of("templateKey", "exploration-drilling"));
        for (Object p : admin.get("/api/v1/positions").list()) {
            positionIds.put((String) ((Map<?, ?>) p).get("code"), (String) ((Map<?, ?>) p).get("id"));
        }
        for (Object t : admin.get("/api/v1/asset-types").list()) {
            typeIds.put((String) ((Map<?, ?>) t).get("code"), (String) ((Map<?, ?>) t).get("id"));
        }
        projectA = admin.post("/api/v1/projects", project("ASG-A", null)).str("id");
        projectB = admin.post("/api/v1/projects", project("ASG-B", null)).str("id");

        // Супервайзер з входом, призначений на A
        String supervisorPerson = hr.post("/api/v1/personnel",
                person("Supervisor", "+380678000002", "supervisor")).str("id");
        hr.post("/api/v1/personnel/" + supervisorPerson + "/login", Map.of());
        Response assigned = admin.post("/api/v1/projects/" + projectA + "/personnel-assignments",
                Map.of("personnelId", supervisorPerson, "startDate", start.toString()));
        assertThat(assigned.status()).isEqualTo(201);
        assertThat(assigned.str("projectRole")).isEqualTo("supervisor");
        assertThat(assigned.at("active")).isEqualTo(true);

        // Інший виконавець бачить A, але не супервайзер
        String otherUser = admin.get("/api/v1/members").list().stream().map(m -> (Map<?, ?>) m)
                .filter(m -> "+380678000003".equals(m.get("phone"))).findFirst().orElseThrow().get("userId").toString();
        admin.post("/api/v1/projects/" + projectA + "/access", Map.of("userId", otherUser));
    }

    @Test
    void equipmentCannotBeOnTwoProjectsAtOnce() {
        String rig = asset("drill_rig", "ASG-RIG-1", Map.of("model", "LF90"));
        Response first = admin.post("/api/v1/projects/" + projectA + "/equipment-assignments",
                Map.of("assetId", rig, "startDate", start.toString()));
        assertThat(first.status()).isEqualTo(201);
        assertThat(first.str("category")).isEqualTo("primary");

        Response overlap = admin.post("/api/v1/projects/" + projectB + "/equipment-assignments",
                Map.of("assetId", rig, "startDate", start.plusDays(10).toString()));
        assertThat(overlap.status()).isEqualTo(409);
        assertThat(overlap.str("error")).isEqualTo("overlap");

        // Бурова переїжджає: на A до дня 9, на B з дня 10
        admin.put("/api/v1/projects/" + projectA + "/equipment-assignments/" + first.str("id"),
                dates(start, start.plusDays(9)));
        assertThat(admin.post("/api/v1/projects/" + projectB + "/equipment-assignments",
                Map.of("assetId", rig, "startDate", start.plusDays(10).toString())).status()).isEqualTo(201);
        assertThat(admin.get("/api/v1/projects/" + projectA + "/equipment-assignments").list())
                .filteredOn(a -> "ASG-RIG-1".equals(((Map<?, ?>) a).get("assetCode"))).hasSize(1);
    }

    @Test
    void auxiliaryEquipmentCanServeSeveralProjects() {
        String truck = asset("vehicle", "ASG-TRK-1", Map.of("plate", "AA0001AA"));
        String rig = asset("drill_rig", "ASG-RIG-2", Map.of("model", "LF70"));
        assertThat(admin.post("/api/v1/projects/" + projectA + "/equipment-assignments",
                Map.of("assetId", truck, "startDate", start.toString())).status()).isEqualTo(201);
        assertThat(admin.post("/api/v1/projects/" + projectB + "/equipment-assignments",
                Map.of("assetId", truck, "startDate", start.toString())).status())
                .as("допоміжне — на двох проєктах одночасно").isEqualTo(201);
        assertThat(admin.post("/api/v1/projects/" + projectA + "/equipment-assignments",
                Map.of("assetId", truck, "startDate", start.plusDays(5).toString())).str("error"))
                .as("але не двічі на тому самому").isEqualTo("overlap");

        admin.post("/api/v1/projects/" + projectA + "/equipment-assignments",
                Map.of("assetId", rig, "startDate", start.toString(), "endDate", start.plusDays(60).toString()));
        Map<String, Map<?, ?>> available = new HashMap<>();
        for (Object item : admin.get("/api/v1/projects/" + projectB + "/available-equipment").list()) {
            available.put((String) ((Map<?, ?>) item).get("assetCode"), (Map<?, ?>) item);
        }
        assertThat(available.get("ASG-RIG-2").get("available")).isEqualTo(false);
        assertThat(available.get("ASG-RIG-2").get("busyProjectCode")).isEqualTo("ASG-A");
        assertThat(available.get("ASG-RIG-2").get("busyUntil")).isEqualTo(start.plusDays(60).toString());
        assertThat(available.get("ASG-TRK-1").get("onThisProject")).isEqualTo(true);

        Map<String, Map<?, ?>> later = new HashMap<>();
        for (Object item : admin.get("/api/v1/projects/" + projectB + "/available-equipment?startDate="
                + start.plusDays(61)).list()) {
            later.put((String) ((Map<?, ?>) item).get("assetCode"), (Map<?, ?>) item);
        }
        assertThat(later.get("ASG-RIG-2").get("available")).as("після переїзду вільна").isEqualTo(true);
    }

    @Test
    void onlyTopLevelEquipmentWithinProjectDates() {
        String camp = asset("camp", "ASG-CAMP", Map.of());
        Map<String, Object> tank = new HashMap<>();
        tank.put("typeId", typeIds.get("fuel_tank"));
        tank.put("parentId", camp);
        tank.put("code", "ASG-TANK");
        tank.put("attributes", Map.of("capacity", 5000));
        String tankId = admin.post("/api/v1/assets", tank).str("id");
        assertThat(tankId).isNotNull();
        assertThat(admin.post("/api/v1/projects/" + projectA + "/equipment-assignments",
                Map.of("assetId", tankId, "startDate", start.toString())).field("assetId")).isEqualTo("nested_equipment");
        assertThat(admin.post("/api/v1/projects/" + projectA + "/equipment-assignments",
                Map.of("assetId", camp, "startDate", start.minusDays(1).toString())).field("startDate"))
                .isEqualTo("before_project_start");
    }

    @Test
    void personCannotBeOnTwoProjectsAtOnce() {
        String driller = hr.post("/api/v1/personnel", person("Driller", null, "driller")).str("id");
        assertThat(admin.post("/api/v1/projects/" + projectA + "/personnel-assignments",
                Map.of("personnelId", driller, "startDate", start.toString(),
                        "endDate", start.plusDays(27).toString())).status()).isEqualTo(201);
        assertThat(admin.post("/api/v1/projects/" + projectB + "/personnel-assignments",
                Map.of("personnelId", driller, "startDate", start.plusDays(27).toString())).str("error"))
                .as("останній день вахти — ще на A").isEqualTo("overlap");
        assertThat(admin.post("/api/v1/projects/" + projectB + "/personnel-assignments",
                Map.of("personnelId", driller, "startDate", start.plusDays(28).toString())).status()).isEqualTo(201);
        assertThat(hr.post("/api/v1/personnel/" + driller + "/archive", Map.of()).str("error"))
                .isEqualTo("has_active_assignment");
        assertThat(hr.get("/api/v1/personnel/" + driller + "/assignments").list()).hasSize(2);
    }

    @Test
    void assignedPersonWithLoginSeesProject() {
        assertThat(supervisor.get("/api/v1/projects").list())
                .extracting(p -> (Object) ((Map<?, ?>) p).get("code")).containsExactly("ASG-A");
        assertThat(supervisor.get("/api/v1/projects/" + projectA + "/personnel-assignments").status()).isEqualTo(200);
        assertThat(supervisor.get("/api/v1/projects/" + projectB).status()).isEqualTo(404);
    }

    @Test
    void supervisorAddsCrewOnlyWithAllowedPositions() {
        String path = "/api/v1/projects/" + projectA + "/crew";
        Response helper = supervisor.post(path, crew("New Helper", "driller_helper"));
        assertThat(helper.status()).isEqualTo(201);
        assertThat(helper.at("hasLogin")).isEqualTo(false);
        assertThat(helper.str("employmentType")).isEqualTo("hired");

        assertThat(supervisor.post(path, crew("Store", "storekeeper")).field("positionId"))
                .isEqualTo("not_allowed_for_supervisor");
        assertThat(supervisor.post("/api/v1/projects/" + projectB + "/crew", crew("Elsewhere", "driller")).status())
                .as("чужий проєкт не видно").isEqualTo(404);
        assertThat(otherWorker.post(path, crew("Not Mine", "driller")).str("error")).isEqualTo("not_project_supervisor");
        assertThat(supervisor.post("/api/v1/projects/" + projectA + "/personnel-assignments",
                Map.of("personnelId", UUID.randomUUID().toString(), "startDate", start.toString())).status())
                .as("звичайне призначення — лише project.manage").isEqualTo(403);
        assertThat(hr.post(path, crew("Store by head", "storekeeper")).status())
                .as("менеджеру — будь-яка посада").isEqualTo(201);
    }

    private String asset(String type, String code, Map<String, Object> attributes) {
        Map<String, Object> body = new HashMap<>();
        body.put("typeId", typeIds.get(type));
        body.put("code", code);
        body.put("attributes", attributes);
        Response created = admin.post("/api/v1/assets", body);
        assertThat(created.status()).as(created.body() == null ? "" : created.body().toString()).isEqualTo(201);
        return created.str("id");
    }

    private Map<String, Object> person(String fullName, String phone, String position) {
        Map<String, Object> body = new HashMap<>();
        body.put("fullName", fullName);
        body.put("phone", phone);
        body.put("positionId", positionIds.get(position));
        return body;
    }

    private Map<String, Object> crew(String fullName, String position) {
        return Map.of("fullName", fullName, "employmentType", "hired", "positionId", positionIds.get(position),
                "startDate", start.toString());
    }

    private static Map<String, Object> dates(LocalDate from, LocalDate to) {
        return Map.of("startDate", from.toString(), "endDate", to.toString());
    }
}
