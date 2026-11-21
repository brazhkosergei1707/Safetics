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
 * Об'єкти через HTTP: шаблон, перевірка полів за схемою, статуси, ієрархія, версії, ізоляція компаній
 * і дозволи (тип змінює лише власник/адмін, об'єкти — і виконавець).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AssetApiTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "assets-test-password";

    @Value("${local.server.port}")
    int port;

    @Autowired
    PasswordEncoder passwordEncoder;

    TestApi ownerX;
    TestApi workerX;
    TestApi ownerY;

    Map<String, String> typeIdsX = new HashMap<>();

    @BeforeAll
    void setUp() {
        JdbcClient admin = admin();
        UUID companyX = admin.sql("insert into company (name) values ('Assets X') returning id").query(UUID.class).single();
        UUID companyY = admin.sql("insert into company (name) values ('Assets Y') returning id").query(UUID.class).single();
        String hash = passwordEncoder.encode(PASSWORD);
        addUser(admin, companyX, "+380672000001", hash, "owner");
        addUser(admin, companyX, "+380672000002", hash, "worker");
        addUser(admin, companyY, "+380672000003", hash, "owner");

        ownerX = TestApi.loggedIn(port, "+380672000001", PASSWORD);
        workerX = TestApi.loggedIn(port, "+380672000002", PASSWORD);
        ownerY = TestApi.loggedIn(port, "+380672000003", PASSWORD);

        Response installed = ownerX.post("/api/v1/asset-types/install-template", Map.of("templateKey", "exploration-drilling"));
        assertThat(installed.status()).isEqualTo(200);
        assertThat(installed.str("installed")).contains("drill_hole", "drill_rig", "vehicle", "camp", "fuel_tank");
        assertThat(ownerY.post("/api/v1/asset-types/install-template", Map.of("templateKey", "exploration-drilling")).status())
                .isEqualTo(200);

        for (Object type : ownerX.get("/api/v1/asset-types").list()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> t = (Map<String, Object>) type;
            typeIdsX.put((String) t.get("code"), (String) t.get("id"));
        }
    }

    // ---------------------------------------------------------------- шаблон і типи

    @Test
    void installingTemplateAgainSkipsExistingTypes() {
        Response again = ownerX.post("/api/v1/asset-types/install-template", Map.of("templateKey", "exploration-drilling"));
        assertThat(again.status()).isEqualTo(200);
        assertThat((List<?>) again.at("installed")).isEmpty();
        assertThat((List<?>) again.at("skipped")).hasSize(5);
    }

    @Test
    void unknownTemplateIsRejected() {
        Response r = ownerX.post("/api/v1/asset-types/install-template", Map.of("templateKey", "../etc"));
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
        Response created = ownerX.post("/api/v1/asset-types", type);
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.str("code")).isEqualTo("generator");
    }

    @Test
    void invalidTypeDefinitionIsRejected() {
        Map<String, Object> type = Map.of(
                "code", "Bad Code",
                "name", Map.of("en", "Bad"),
                "fields", List.of(
                        Map.of("key", "size", "label", Map.of(), "type", "enum"),
                        Map.of("key", "size", "label", Map.of(), "type", "colour")),
                "statuses", List.of());

        Response r = ownerX.post("/api/v1/asset-types", type);
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.map().get("fields")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> errors = (Map<String, Object>) r.map().get("fields");
        assertThat(errors).containsEntry("code", "invalid_format")
                .containsEntry("fields[0].options", "required")
                .containsEntry("fields[1].key", "duplicate")
                .containsEntry("fields[1].type", "invalid_option")
                .containsEntry("statuses", "required");
    }

    // ---------------------------------------------------------------- об'єкти

    @Test
    void workerCreatesDrillHoleWithDefaultStatus() {
        Map<String, Object> attributes = Map.of("azimuth", 90, "dip", -60, "plannedDepth", "250", "coreSize", "NQ");
        Response r = workerX.post("/api/v1/assets", hole("DDH-100", attributes, null));

        assertThat(r.status()).isEqualTo(201);
        assertThat(r.str("status")).isEqualTo("planned");
        assertThat(r.str("typeCode")).isEqualTo("drill_hole");
        assertThat(((Number) r.at("attributes.plannedDepth")).intValue()).isEqualTo(250);
        assertThat(r.at("version")).isEqualTo(1);

        Response read = workerX.get("/api/v1/assets/" + r.str("id"));
        assertThat(read.status()).isEqualTo(200);
        assertThat(read.str("code")).isEqualTo("DDH-100");
    }

    @Test
    void attributesAreValidatedAgainstTypeSchema() {
        Response r = ownerX.post("/api/v1/assets", hole("DDH-101", Map.of("dip", 120, "colour", "red"), null));
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.str("error")).isEqualTo("validation_failed");
        @SuppressWarnings("unchecked")
        Map<String, Object> errors = (Map<String, Object>) r.map().get("fields");
        assertThat(errors).containsEntry("attributes.dip", "too_large").containsEntry("attributes.colour", "unknown_field");
    }

    @Test
    void requiredAttributeAndUnknownStatusAreRejected() {
        Response noModel = ownerX.post("/api/v1/assets", Map.of("typeId", typeIdsX.get("drill_rig"), "code", "RIG-X"));
        assertThat(noModel.status()).isEqualTo(400);
        @SuppressWarnings("unchecked")
        Map<String, Object> errors = (Map<String, Object>) noModel.map().get("fields");
        assertThat(errors).containsEntry("attributes.model", "required");

        Response badStatus = ownerX.post("/api/v1/assets", hole("DDH-102", Map.of(), "flying"));
        assertThat(badStatus.status()).isEqualTo(400);
        assertThat(badStatus.str("fields.status")).isEqualTo("invalid_option");
    }

    @Test
    void duplicateCodeWithinTypeIsConflict() {
        assertThat(ownerX.post("/api/v1/assets", hole("DDH-103", Map.of(), null)).status()).isEqualTo(201);
        Response duplicate = ownerX.post("/api/v1/assets", hole("DDH-103", Map.of(), null));
        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(duplicate.str("error")).isEqualTo("duplicate");
    }

    @Test
    void updateRequiresCurrentVersion() {
        Response created = ownerX.post("/api/v1/assets", hole("DDH-104", Map.of("plannedDepth", 200), null));
        String id = created.str("id");

        Map<String, Object> update = new HashMap<>();
        update.put("code", "DDH-104");
        update.put("status", "drilling");
        update.put("attributes", Map.of("plannedDepth", 200, "actualDepth", 35.5));
        update.put("version", 1);
        Response updated = ownerX.put("/api/v1/assets/" + id, update);
        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.at("version")).isEqualTo(2);
        assertThat(updated.str("status")).isEqualTo("drilling");

        // Хтось інший зберігає зі старою версією
        Response stale = ownerX.put("/api/v1/assets/" + id, update);
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.str("error")).isEqualTo("stale_version");
    }

    @Test
    void hierarchyFollowsAllowedChildTypes() {
        Response camp = ownerX.post("/api/v1/assets",
                Map.of("typeId", typeIdsX.get("camp"), "code", "CAMP-1", "attributes", Map.of("capacity", 40)));
        assertThat(camp.status()).isEqualTo(201);

        Map<String, Object> tank = new HashMap<>();
        tank.put("typeId", typeIdsX.get("fuel_tank"));
        tank.put("code", "TANK-1");
        tank.put("parentId", camp.str("id"));
        tank.put("attributes", Map.of("capacity", 20000, "fuelType", "diesel"));
        assertThat(ownerX.post("/api/v1/assets", tank).status()).isEqualTo(201);

        // Ємність не може бути вкладена у свердловину
        Response hole = ownerX.post("/api/v1/assets", hole("DDH-105", Map.of(), null));
        tank.put("code", "TANK-2");
        tank.put("parentId", hole.str("id"));
        Response wrongParent = ownerX.post("/api/v1/assets", tank);
        assertThat(wrongParent.status()).isEqualTo(400);
        assertThat(wrongParent.str("fields.parentId")).isEqualTo("child_type_not_allowed");

        // Табір з активною ємністю не архівується
        Response archive = ownerX.post("/api/v1/assets/" + camp.str("id") + "/archive", Map.of());
        assertThat(archive.status()).isEqualTo(409);
        assertThat(archive.str("error")).isEqualTo("has_active_children");
    }

    @Test
    void archivedAssetsAreHiddenFromListByDefault() {
        Response created = ownerX.post("/api/v1/assets", hole("DDH-106", Map.of(), null));
        String id = created.str("id");
        assertThat(ownerX.post("/api/v1/assets/" + id + "/archive", Map.of()).str("archived")).isEqualTo("true");

        Response list = ownerX.get("/api/v1/assets?q=DDH-106");
        assertThat(((Number) list.at("total")).intValue()).isZero();
        Response withArchived = ownerX.get("/api/v1/assets?q=DDH-106&includeArchived=true");
        assertThat(((Number) withArchived.at("total")).intValue()).isEqualTo(1);

        assertThat(ownerX.post("/api/v1/assets/" + id + "/restore", Map.of()).str("archived")).isEqualTo("false");
    }

    @Test
    void otherCompanyCannotSeeAssets() {
        Response created = ownerX.post("/api/v1/assets", hole("DDH-107", Map.of(), null));
        String id = created.str("id");

        assertThat(ownerY.get("/api/v1/assets/" + id).status()).isEqualTo(404);
        assertThat(((Number) ownerY.get("/api/v1/assets?q=DDH-107").at("total")).intValue()).isZero();

        // Тип компанії X для компанії Y не існує
        Response foreignType = ownerY.post("/api/v1/assets", hole("DDH-107", Map.of(), null));
        assertThat(foreignType.status()).isEqualTo(400);
        assertThat(foreignType.str("fields.typeId")).isEqualTo("not_found");
    }

    @Test
    void changesAreWrittenToAuditLog() {
        Response created = ownerX.post("/api/v1/assets", hole("DDH-108", Map.of(), null));
        Response audit = ownerX.get("/api/v1/audit-log?limit=200");
        assertThat(audit.list()).anySatisfy(entry -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> e = (Map<String, Object>) entry;
            assertThat(e.get("entity")).isEqualTo("asset");
            assertThat(e.get("entityId")).isEqualTo(created.str("id"));
            assertThat(e.get("action")).isEqualTo("create");
        });
    }

    // ---------------------------------------------------------------- допоміжне

    private Map<String, Object> hole(String code, Map<String, Object> attributes, String status) {
        Map<String, Object> body = new HashMap<>();
        body.put("typeId", typeIdsX.get("drill_hole"));
        body.put("code", code);
        body.put("attributes", attributes);
        if (status != null) {
            body.put("status", status);
        }
        return body;
    }

    private static void addUser(JdbcClient admin, UUID company, String phone, String hash, String role) {
        UUID user = admin.sql("insert into app_user (phone, full_name, password_hash) values (?, ?, ?) returning id")
                .params(phone, "User " + phone, hash).query(UUID.class).single();
        admin.sql("insert into membership (company_id, user_id, role_id) select ?, ?, id from role where code = ? and company_id is null")
                .params(company, user, role).update();
    }
}
