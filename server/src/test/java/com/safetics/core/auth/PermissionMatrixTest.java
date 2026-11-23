package com.safetics.core.auth;

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
 * Матриця прав (safetics-context.md): головний менеджер і відповідальний менеджер проєкту,
 * замовники й обладнання в межах своїх проєктів, задачі бурильника (свої, змін у бригаді, по його
 * обладнанню), зведений звіт для порталу.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PermissionMatrixTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "matrix-test-password";

    @Value("${local.server.port}")
    int port;

    @Autowired
    PasswordEncoder passwordEncoder;

    TestApi admin;
    /** Виконавців заводить головний менеджер (адмін не має personnel.manage) */
    TestApi hr;
    TestApi head;
    TestApi manager;
    TestApi driller;
    TestApi client;
    UUID managerId;
    UUID headId;
    UUID manager2Id;
    UUID drillerId;
    String customerA;
    String customerB;
    String projectA;
    String projectB;
    String rigA;
    String rigB;
    Map<String, String> taskTypes = new HashMap<>();

    @BeforeAll
    void setUp() {
        UUID company = addCompany("Matrix Co");
        String hash = passwordEncoder.encode(PASSWORD);
        addMember(company, "+380679930001", hash, "admin");
        addMember(company, "+380679930099", hash, "head_manager");
        headId = addMember(company, "+380679930002", hash, "head_manager");
        managerId = addMember(company, "+380679930003", hash, "manager");
        manager2Id = addMember(company, "+380679930004", hash, "manager");
        drillerId = addMember(company, "+380679930005", hash, "worker");
        admin = TestApi.loggedIn(port, "+380679930001", PASSWORD);
        hr = TestApi.loggedIn(port, "+380679930099", PASSWORD);
        head = TestApi.loggedIn(port, "+380679930002", PASSWORD);
        manager = TestApi.loggedIn(port, "+380679930003", PASSWORD);
        admin.post("/api/v1/asset-types/install-template", Map.of("templateKey", "exploration-drilling"));
        for (Object t : admin.get("/api/v1/task-types").list()) {
            taskTypes.put((String) ((Map<?, ?>) t).get("code"), (String) ((Map<?, ?>) t).get("id"));
        }

        customerA = head.post("/api/v1/customers", Map.of("name", "Matrix Customer A")).str("id");
        customerB = head.post("/api/v1/customers", Map.of("name", "Matrix Customer B")).str("id");
        Map<String, Object> a = project("MX-A", customerA);
        a.put("managerId", managerId.toString());
        projectA = head.post("/api/v1/projects", a).str("id");
        projectB = head.post("/api/v1/projects", project("MX-B", customerB)).str("id");

        String rigType = admin.get("/api/v1/asset-types").list().stream().<Map<?, ?>>map(t -> (Map<?, ?>) t)
                .filter(t -> "drill_rig".equals(t.get("code"))).findFirst().orElseThrow().get("id").toString();
        rigA = admin.post("/api/v1/assets", Map.of("typeId", rigType, "code", "MX-RIG-A", "attributes", Map.of("model", "LF90")))
                .str("id");
        rigB = admin.post("/api/v1/assets", Map.of("typeId", rigType, "code", "MX-RIG-B", "attributes", Map.of("model", "LF90")))
                .str("id");
        String start = LocalDate.now(ZoneOffset.UTC).minusDays(10).toString();
        admin.post("/api/v1/projects/" + projectA + "/equipment-assignments", Map.of("assetId", rigA, "startDate", start));
        admin.post("/api/v1/projects/" + projectB + "/equipment-assignments", Map.of("assetId", rigB, "startDate", start));

        String position = admin.get("/api/v1/positions").list().stream().<Map<?, ?>>map(p -> (Map<?, ?>) p)
                .filter(p -> "driller".equals(p.get("code"))).findFirst().orElseThrow().get("id").toString();
        Map<String, Object> person = new HashMap<>();
        person.put("fullName", "Matrix Driller");
        person.put("phone", "+380679930005");
        person.put("positionId", position);
        String personId = hr.post("/api/v1/personnel", person).str("id");
        hr.post("/api/v1/personnel/" + personId + "/login", Map.of());
        admin.post("/api/v1/projects/" + projectA + "/personnel-assignments", Map.of("personnelId", personId, "startDate", start));
        driller = TestApi.loggedIn(port, "+380679930005", PASSWORD);

        // Зміна на RIG-A з бурильником у бригаді
        Response shift = admin.post("/api/v1/projects/" + projectA + "/shifts", Map.of("assetId", rigA,
                "date", LocalDate.now(ZoneOffset.UTC).minusDays(1).toString(), "shiftCode", "day"));
        admin.put("/api/v1/shifts/" + shift.str("id") + "/crew", Map.of("version", shift.at("version"),
                "crew", List.of(Map.of("personnelId", personId, "lead", true))));

        Map<String, Object> contact = new HashMap<>();
        contact.put("fullName", "Matrix Geologist");
        contact.put("phone", "+380679930006");
        String contactId = admin.post("/api/v1/customers/" + customerA + "/contacts", contact).str("id");
        admin.post("/api/v1/customer-contacts/" + contactId + "/portal-access",
                Map.of("role", "client_rep", "password", "client-password"));
        client = TestApi.loggedIn(port, "+380679930006", "client-password");
    }

    @Test
    void customersAndProjects() {
        assertThat(ids(head.get("/api/v1/customers"))).contains(customerA, customerB);
        assertThat(ids(manager.get("/api/v1/customers"))).as("лише замовники своїх проєктів").containsExactly(customerA);
        assertThat(manager.get("/api/v1/customers/" + customerB).status()).isEqualTo(404);
        assertThat(manager.post("/api/v1/customers", Map.of("name", "By manager")).status())
                .as("замовників створює головний менеджер").isEqualTo(403);
        assertThat(driller.get("/api/v1/customers").status()).isEqualTo(403);

        assertThat(ids(manager.get("/api/v1/projects"))).containsExactly(projectA);
        assertThat(manager.post("/api/v1/projects", project("MX-NOPE", customerA)).status()).isEqualTo(403);
        assertThat(manager.post("/api/v1/projects/" + projectA + "/close", Map.of()).status()).isEqualTo(403);

        // Менеджер не перепризначає відповідального; головний менеджер — так, і новий отримує доступ
        Response a = manager.get("/api/v1/projects/" + projectA);
        assertThat(a.str("managerId")).isEqualTo(managerId.toString());
        Map<String, Object> update = updateBody(a);
        update.put("managerId", manager2Id.toString());
        assertThat(manager.put("/api/v1/projects/" + projectA, update).str("managerId")).isEqualTo(managerId.toString());
        Response b = head.get("/api/v1/projects/" + projectB);
        Map<String, Object> assign = updateBody(b);
        assign.put("managerId", manager2Id.toString());
        assertThat(head.put("/api/v1/projects/" + projectB, assign).str("managerId")).isEqualTo(manager2Id.toString());
        assertThat(head.get("/api/v1/projects/" + projectB + "/access").list())
                .extracting(x -> (Object) ((Map<?, ?>) x).get("userId")).contains(manager2Id.toString());
        Map<String, Object> wrong = updateBody(head.get("/api/v1/projects/" + projectB));
        wrong.put("managerId", drillerId.toString());
        assertThat(head.put("/api/v1/projects/" + projectB, wrong).field("managerId")).isEqualTo("not_manager");
        wrong.put("managerId", headId.toString());
        assertThat(head.put("/api/v1/projects/" + projectB, wrong).field("managerId"))
                .as("відповідальний — лише роль «Менеджер»").isEqualTo("not_manager");
    }

    @Test
    void equipmentScope() {
        assertThat(ids(manager.get("/api/v1/assets?limit=200"))).as("менеджер бачить усе обладнання").contains(rigA, rigB);
        Response foreign = manager.get("/api/v1/assets/" + rigB);
        Map<String, Object> body = new HashMap<>();
        body.put("code", "MX-RIG-B");
        body.put("attributes", Map.of("model", "LF95"));
        body.put("status", foreign.str("status"));
        body.put("version", foreign.at("version"));
        assertThat(manager.put("/api/v1/assets/" + rigB, body).str("error")).isEqualTo("asset_on_other_project");
        assertThat(ids(driller.get("/api/v1/assets?limit=200"))).as("бурильник — лише обладнання своїх проєктів")
                .contains(rigA).doesNotContain(rigB);
        assertThat(driller.get("/api/v1/assets/" + rigB).status()).isEqualTo(404);
    }

    @Test
    void drillerSeesOwnWork() {
        Map<String, Object> service = new HashMap<>();
        service.put("typeId", taskTypes.get("service"));
        service.put("title", "Replace hose on MX-RIG-A");
        service.put("assetId", rigA);
        service.put("attributes", Map.of("criticality", "medium"));
        String serviceTask = admin.post("/api/v1/projects/" + projectA + "/tasks", service).str("id");
        Map<String, Object> general = new HashMap<>();
        general.put("typeId", taskTypes.get("general"));
        general.put("title", "Office paperwork");
        String officeTask = admin.post("/api/v1/projects/" + projectA + "/tasks", general).str("id");

        List<String> visible = ids(driller.get("/api/v1/projects/" + projectA + "/tasks"));
        assertThat(visible).as("сервіс по його буровій").contains(serviceTask).doesNotContain(officeTask);
        assertThat(driller.get("/api/v1/tasks/" + officeTask).status()).isEqualTo(404);
        assertThat(ids(manager.get("/api/v1/projects/" + projectA + "/tasks"))).contains(serviceTask, officeTask);
        assertThat(((List<?>) driller.get("/api/v1/projects/" + projectA + "/shift-board").at("shifts")))
                .as("зміна, де він у бригаді").hasSize(1);
    }

    @Test
    void portalSummaryIsLimited() {
        Response summary = client.get("/api/v1/reports/summary?from=" + LocalDate.now(ZoneOffset.UTC).minusDays(30) + "&to="
                + LocalDate.now(ZoneOffset.UTC));
        assertThat(summary.status()).isEqualTo(200);
        Map<?, ?> total = (Map<?, ?>) summary.at("total");
        assertThat(total.get("nonBillableMinutes")).isEqualTo(0);
        assertThat((List<?>) total.get("consumables")).isEmpty();
        assertThat(summary.at("includeDrafts")).isEqualTo(false);
    }

    private static Map<String, Object> project(String code, String customer) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("name", "Project " + code);
        body.put("customerId", customer);
        body.put("timeZone", "UTC");
        body.put("startDate", LocalDate.now(ZoneOffset.UTC).minusDays(30).toString());
        body.put("shifts", List.of(Map.of("code", "day", "name", "Day", "start", "06:00", "durationMinutes", 720)));
        return body;
    }

    private static Map<String, Object> updateBody(Response project) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", project.str("code"));
        body.put("name", project.str("name"));
        body.put("customerId", project.str("customerId"));
        body.put("timeZone", project.str("timeZone"));
        body.put("startDate", project.str("startDate"));
        body.put("managerId", project.str("managerId"));
        body.put("version", project.at("version"));
        return body;
    }

    private static List<String> ids(Response response) {
        List<?> data = response.body() instanceof List<?> list ? list : (List<?>) response.map().get("items");
        return data.stream().map(x -> (String) ((Map<?, ?>) x).get("id")).toList();
    }
}
