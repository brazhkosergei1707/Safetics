package com.safetics.assets;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.safetics.AbstractIntegrationTest;
import com.safetics.TestApi;
import com.safetics.TestApi.Response;

/**
 * Обладнання через HTTP: шаблон, категорії, перевірка полів за схемою, статуси, ієрархія, версії,
 * ізоляція компаній і дозволи (тип змінює лише адмін, обладнання — і виконавець).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AssetApiTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "assets-test-password";

    @Value("${local.server.port}")
    int port;

    @Autowired
    PasswordEncoder passwordEncoder;

    TestApi adminX;
    TestApi workerX;
    TestApi adminY;

    Map<String, String> typeIdsX = new HashMap<>();

    @BeforeAll
    void setUp() {
        JdbcClient admin = admin();
        UUID companyX = admin.sql("insert into company (name) values ('Assets X') returning id").query(UUID.class).single();
        UUID companyY = admin.sql("insert into company (name) values ('Assets Y') returning id").query(UUID.class).single();
        String hash = passwordEncoder.encode(PASSWORD);
        addUser(admin, companyX, "+380672000001", hash, "admin");
        addUser(admin, companyX, "+380672000002", hash, "worker");
        addUser(admin, companyY, "+380672000003", hash, "admin");

        adminX = TestApi.loggedIn(port, "+380672000001", PASSWORD);
        workerX = TestApi.loggedIn(port, "+380672000002", PASSWORD);
        adminY = TestApi.loggedIn(port, "+380672000003", PASSWORD);

        Response installed = adminX.post("/api/v1/asset-types/install-template", Map.of("templateKey", "exploration-drilling"));
        assertThat(installed.status()).isEqualTo(200);
        assertThat(installed.str("installed")).contains("drill_rig", "vehicle", "camp", "fuel_tank");
        assertThat(adminY.post("/api/v1/asset-types/install-template", Map.of("templateKey", "exploration-drilling")).status())
                .isEqualTo(200);

        for (Object type : adminX.get("/api/v1/asset-types").list()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> t = (Map<String, Object>) type;
            typeIdsX.put((String) t.get("code"), (String) t.get("id"));
        }
    }

    // ---------------------------------------------------------------- шаблон і типи

    @Test
    void templateHasEquipmentCategoriesAndNoDrillHole() {
        assertThat(typeIdsX).containsOnlyKeys("drill_rig", "vehicle", "camp", "fuel_tank");
        Response rig = adminX.get("/api/v1/asset-types/" + typeIdsX.get("drill_rig"));
        assertThat(rig.str("category")).isEqualTo("primary");
        Response vehicle = adminX.get("/api/v1/asset-types/" + typeIdsX.get("vehicle"));
        assertThat(vehicle.str("category")).isEqualTo("auxiliary");
    }

    @Test
    void installingTemplateAgainSkipsExistingTypes() {
        Response again = adminX.post("/api/v1/asset-types/install-template", Map.of("templateKey", "exploration-drilling"));
        assertThat(again.status()).isEqualTo(200);
        assertThat((List<?>) again.at("installed")).isEmpty();
        assertThat((List<?>) again.at("skipped")).hasSize(4);
    }

    @Test
    void unknownTemplateIsRejected() {
        Response r = adminX.post("/api/v1/asset-types/install-template", Map.of("templateKey", "../etc"));
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.str("fields.templateKey")).isEqualTo("unknown_template");
    }

    @Test
    void onlyCatalogManagersChangeTypes() {
        Map<String, Object> type = Map.of(
                "code", "generator",
                "name", Map.of("en", "Generator"),
                "fields", List.of(Map.of("key", "powerKw", "label", Map.of("en", "Power"), "type", "number", "unit", "kW")),
                "statuses", List.of(Map.of("code", "active", "label", Map.of("en", "Active"))));

        assertThat(workerX.post("/api/v1/asset-types", type).status()).isEqualTo(403);
        Response created = adminX.post("/api/v1/asset-types", type);
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.str("code")).isEqualTo("generator");
        assertThat(created.str("category")).isEqualTo("auxiliary");
    }

    @Test
    void invalidTypeDefinitionIsRejected() {
        Map<String, Object> type = Map.of(
                "code", "Bad Code",
                "name", Map.of("en", "Bad"),
                "category", "secondary",
                "fields", List.of(
                        Map.of("key", "size", "label", Map.of(), "type", "enum"),
                        Map.of("key", "size", "label", Map.of(), "type", "colour")),
                "statuses", List.of());

        Response r = adminX.post("/api/v1/asset-types", type);
        assertThat(r.status()).isEqualTo(400);
        assertThat(errors(r)).containsEntry("code", "invalid_format")
                .containsEntry("category", "invalid_option")
                .containsEntry("fields[0].options", "required")
                .containsEntry("fields[1].key", "duplicate")
                .containsEntry("fields[1].type", "invalid_option")
                .containsEntry("statuses", "required");
    }

    // ---------------------------------------------------------------- обладнання

    @Test
    void adminCreatesRigWithDefaultStatus() {
        assertThat(workerX.post("/api/v1/assets", rig("RIG-099", Map.of("year", "2018"), null)).status())
                .as("виконавець обладнання не змінює").isEqualTo(403);
        Response r = adminX.post("/api/v1/assets", rig("RIG-100", Map.of("year", "2018", "engineHours", 1200), null));

        assertThat(r.status()).isEqualTo(201);
        assertThat(r.str("status")).isEqualTo("active");
        assertThat(r.str("typeCode")).isEqualTo("drill_rig");
        assertThat(((Number) r.at("attributes.year")).intValue()).isEqualTo(2018);
        assertThat(r.at("version")).isEqualTo(1);

        Response read = adminX.get("/api/v1/assets/" + r.str("id"));
        assertThat(read.status()).isEqualTo(200);
        assertThat(read.str("code")).isEqualTo("RIG-100");
        assertThat(workerX.get("/api/v1/assets/" + r.str("id")).status())
                .as("виконавець бачить лише обладнання своїх проєктів").isEqualTo(404);
    }

    @Test
    void attributesAreValidatedAgainstTypeSchema() {
        Response r = adminX.post("/api/v1/assets", rig("RIG-101", Map.of("year", 1900, "colour", "red"), null));
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.str("error")).isEqualTo("validation_failed");
        assertThat(errors(r)).containsEntry("attributes.year", "too_small").containsEntry("attributes.colour", "unknown_field");
    }

    @Test
    void requiredAttributeAndUnknownStatusAreRejected() {
        Response noModel = adminX.post("/api/v1/assets", Map.of("typeId", typeIdsX.get("drill_rig"), "code", "RIG-X"));
        assertThat(noModel.status()).isEqualTo(400);
        assertThat(errors(noModel)).containsEntry("attributes.model", "required");

        Response badStatus = adminX.post("/api/v1/assets", rig("RIG-102", Map.of(), "flying"));
        assertThat(badStatus.status()).isEqualTo(400);
        assertThat(badStatus.str("fields.status")).isEqualTo("invalid_option");
    }

    @Test
    void duplicateCodeWithinTypeIsConflict() {
        assertThat(adminX.post("/api/v1/assets", rig("RIG-103", Map.of(), null)).status()).isEqualTo(201);
        Response duplicate = adminX.post("/api/v1/assets", rig("RIG-103", Map.of(), null));
        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(duplicate.str("error")).isEqualTo("duplicate");
    }

    @Test
    void updateRequiresCurrentVersion() {
        Response created = adminX.post("/api/v1/assets", rig("RIG-104", Map.of("engineHours", 100), null));
        String id = created.str("id");

        Map<String, Object> update = new HashMap<>();
        update.put("code", "RIG-104");
        update.put("status", "maintenance");
        update.put("attributes", Map.of("model", "LF90", "engineHours", 135.5));
        update.put("version", 1);
        Response updated = adminX.put("/api/v1/assets/" + id, update);
        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.at("version")).isEqualTo(2);
        assertThat(updated.str("status")).isEqualTo("maintenance");

        // Хтось інший зберігає зі старою версією
        Response stale = adminX.put("/api/v1/assets/" + id, update);
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.str("error")).isEqualTo("stale_version");
    }

    @Test
    void hierarchyFollowsAllowedChildTypes() {
        Response camp = adminX.post("/api/v1/assets",
                Map.of("typeId", typeIdsX.get("camp"), "code", "CAMP-1", "attributes", Map.of("capacity", 40)));
        assertThat(camp.status()).isEqualTo(201);

        Map<String, Object> tank = new HashMap<>();
        tank.put("typeId", typeIdsX.get("fuel_tank"));
        tank.put("code", "TANK-1");
        tank.put("parentId", camp.str("id"));
        tank.put("attributes", Map.of("capacity", 20000, "fuelType", "diesel"));
        assertThat(adminX.post("/api/v1/assets", tank).status()).isEqualTo(201);

        // Ємність не може бути вкладена в бурову
        Response rig = adminX.post("/api/v1/assets", rig("RIG-105", Map.of(), null));
        tank.put("code", "TANK-2");
        tank.put("parentId", rig.str("id"));
        Response wrongParent = adminX.post("/api/v1/assets", tank);
        assertThat(wrongParent.status()).isEqualTo(400);
        assertThat(wrongParent.str("fields.parentId")).isEqualTo("child_type_not_allowed");

        // Табір з активною ємністю не архівується
        Response archive = adminX.post("/api/v1/assets/" + camp.str("id") + "/archive", Map.of());
        assertThat(archive.status()).isEqualTo(409);
        assertThat(archive.str("error")).isEqualTo("has_active_children");
    }

    @Test
    void archivedAssetsAreHiddenFromListByDefault() {
        Response created = adminX.post("/api/v1/assets", rig("RIG-106", Map.of(), null));
        String id = created.str("id");
        assertThat(adminX.post("/api/v1/assets/" + id + "/archive", Map.of()).str("archived")).isEqualTo("true");

        Response list = adminX.get("/api/v1/assets?q=RIG-106");
        assertThat(((Number) list.at("total")).intValue()).isZero();
        Response withArchived = adminX.get("/api/v1/assets?q=RIG-106&includeArchived=true");
        assertThat(((Number) withArchived.at("total")).intValue()).isEqualTo(1);

        assertThat(adminX.post("/api/v1/assets/" + id + "/restore", Map.of()).str("archived")).isEqualTo("false");
    }

    @Test
    void otherCompanyCannotSeeAssets() {
        Response created = adminX.post("/api/v1/assets", rig("RIG-107", Map.of(), null));
        String id = created.str("id");

        assertThat(adminY.get("/api/v1/assets/" + id).status()).isEqualTo(404);
        assertThat(((Number) adminY.get("/api/v1/assets?q=RIG-107").at("total")).intValue()).isZero();

        // Тип компанії X для компанії Y не існує
        Response foreignType = adminY.post("/api/v1/assets", rig("RIG-107", Map.of(), null));
        assertThat(foreignType.status()).isEqualTo(400);
        assertThat(foreignType.str("fields.typeId")).isEqualTo("not_found");
    }

    @Test
    void changesAreWrittenToAuditLog() {
        Response created = adminX.post("/api/v1/assets", rig("RIG-108", Map.of(), null));
        Response audit = adminX.get("/api/v1/audit-log?limit=200");
        assertThat(audit.list()).anySatisfy(entry -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> e = (Map<String, Object>) entry;
            assertThat(e.get("entity")).isEqualTo("asset");
            assertThat(e.get("entityId")).isEqualTo(created.str("id"));
            assertThat(e.get("action")).isEqualTo("create");
        });
    }

    // ---------------------------------------------------------------- допоміжне

    /** Бурова установка: модель обов'язкова, тож підставляється завжди. */
    private Map<String, Object> rig(String code, Map<String, Object> attributes, String status) {
        Map<String, Object> values = new HashMap<>(attributes);
        values.putIfAbsent("model", "LF90");
        Map<String, Object> body = new HashMap<>();
        body.put("typeId", typeIdsX.get("drill_rig"));
        body.put("code", code);
        body.put("attributes", values);
        if (status != null) {
            body.put("status", status);
        }
        return body;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> errors(Response r) {
        return (Map<String, Object>) r.map().get("fields");
    }

    private static void addUser(JdbcClient admin, UUID company, String phone, String hash, String role) {
        UUID user = admin.sql("insert into app_user (phone, full_name, password_hash) values (?, ?, ?) returning id")
                .params(phone, "User " + phone, hash).query(UUID.class).single();
        admin.sql("insert into membership (company_id, user_id, role_id) select ?, ?, id from role where code = ? and company_id is null")
                .params(company, user, role).update();
    }
}
