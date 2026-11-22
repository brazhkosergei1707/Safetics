package com.safetics.projects;

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

/** Закриття проєкту звільняє людей і обладнання: їх одразу можна призначити на інший проєкт. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProjectCloseTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "close-test-password";

    @Value("${local.server.port}")
    int port;

    @Autowired
    PasswordEncoder passwordEncoder;

    TestApi admin;
    String driller;
    String helper;
    String rig;

    @BeforeAll
    void setUp() {
        UUID company = addCompany("Close Co");
        addMember(company, "+380679800001", passwordEncoder.encode(PASSWORD), "admin");
        admin = TestApi.loggedIn(port, "+380679800001", PASSWORD);
        admin.post("/api/v1/asset-types/install-template", Map.of("templateKey", "exploration-drilling"));
        String position = admin.get("/api/v1/positions").list().stream().<Map<?, ?>>map(p -> (Map<?, ?>) p)
                .filter(p -> "driller".equals(p.get("code"))).findFirst().orElseThrow().get("id").toString();
        String rigType = admin.get("/api/v1/asset-types").list().stream().<Map<?, ?>>map(t -> (Map<?, ?>) t)
                .filter(t -> "drill_rig".equals(t.get("code"))).findFirst().orElseThrow().get("id").toString();
        driller = admin.post("/api/v1/personnel", Map.of("fullName", "Close Driller", "positionId", position)).str("id");
        helper = admin.post("/api/v1/personnel", Map.of("fullName", "Close Helper", "positionId", position)).str("id");
        rig = admin.post("/api/v1/assets", Map.of("typeId", rigType, "code", "RIG-CL", "attributes", Map.of("model", "LF90")))
                .str("id");
    }

    @Test
    void closingReleasesPeopleAndEquipment() {
        String customer = admin.post("/api/v1/customers", Map.of("name", "Close Customer")).str("id");
        String first = admin.post("/api/v1/projects", project("CL-A", customer)).str("id");
        String second = admin.post("/api/v1/projects", project("CL-B", customer)).str("id");
        String past = LocalDate.now().minusDays(10).toString();
        admin.post(path(first, "personnel-assignments"), Map.of("personnelId", driller, "startDate", past));
        admin.post(path(first, "personnel-assignments"),
                Map.of("personnelId", helper, "startDate", LocalDate.now().plusDays(5).toString()));
        admin.post(path(first, "equipment-assignments"), Map.of("assetId", rig, "startDate", past));
        String rigTask = admin.get(path(first, "tasks")).list().stream().<Map<?, ?>>map(t -> (Map<?, ?>) t)
                .filter(t -> "rig_on_project".equals(t.get("typeCode"))).findFirst().orElseThrow().get("id").toString();

        Response closed = admin.post("/api/v1/projects/" + first + "/close", Map.of());
        assertThat(closed.str("status")).isEqualTo("closed");
        String lastDay = LocalDate.parse(closed.str("endDate")).minusDays(1).toString();

        List<Map<?, ?>> people = admin.get(path(first, "personnel-assignments")).list().stream()
                .<Map<?, ?>>map(a -> (Map<?, ?>) a).toList();
        assertThat(people).as("майбутнє призначення видалено").hasSize(1);
        assertThat(people.getFirst().get("endDate")).isEqualTo(lastDay);
        List<Map<?, ?>> equipment = admin.get(path(first, "equipment-assignments")).list().stream()
                .<Map<?, ?>>map(a -> (Map<?, ?>) a).toList();
        assertThat(equipment.getFirst().get("endDate")).isEqualTo(lastDay);
        assertThat(admin.get("/api/v1/tasks/" + rigTask).str("statusCategory")).isEqualTo("done");

        // У день закриття — уже на іншому проєкті
        String today = closed.str("endDate");
        Map<String, Object> person = new HashMap<>(Map.of("personnelId", driller, "startDate", today));
        assertThat(admin.post(path(second, "personnel-assignments"), person).status()).isEqualTo(201);
        assertThat(admin.post(path(second, "equipment-assignments"), Map.of("assetId", rig, "startDate", today)).status())
                .isEqualTo(201);
    }

    private static String path(String projectId, String what) {
        return "/api/v1/projects/" + projectId + "/" + what;
    }
}
