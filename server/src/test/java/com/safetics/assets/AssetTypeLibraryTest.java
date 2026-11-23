package com.safetics.assets;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
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
 * Бібліотека типів обладнання (типи інших компаній без назв компаній), видалення типу
 * (лише без обладнання в роботі) і порядок вкладок.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AssetTypeLibraryTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "library-test-password";

    @Value("${local.server.port}")
    int port;

    @Autowired
    PasswordEncoder passwordEncoder;

    TestApi adminA;
    TestApi adminB;
    TestApi workerA;

    @BeforeAll
    void setUp() {
        UUID companyA = addCompany("Library A");
        UUID companyB = addCompany("Library B");
        String hash = passwordEncoder.encode(PASSWORD);
        addMember(companyA, "+380674100001", hash, "admin");
        addMember(companyA, "+380674100002", hash, "worker");
        addMember(companyB, "+380674100003", hash, "admin");
        adminA = TestApi.loggedIn(port, "+380674100001", PASSWORD);
        workerA = TestApi.loggedIn(port, "+380674100002", PASSWORD);
        adminB = TestApi.loggedIn(port, "+380674100003", PASSWORD);
        adminA.post("/api/v1/asset-types", type("lib_camp", "Library camp"));
        // Тип компанії B: є в бібліотеці для A; «можна вкладати» — код, якого в A немає
        Map<String, Object> pump = new HashMap<>(type("lib_pump_unit", "Library pump unit"));
        pump.put("category", "primary");
        pump.put("allowedChildTypes", List.of("lib_camp", "lib_unknown"));
        pump.put("checklist", List.of(Map.of("key", "oil", "label", Map.of("en", "Oil level"), "critical", true)));
        assertThat(adminB.post("/api/v1/asset-types", type("lib_unknown", "Unknown")).status()).isEqualTo(201);
        assertThat(adminB.post("/api/v1/asset-types", type("lib_camp", "Library camp")).status()).isEqualTo(201);
        assertThat(adminB.post("/api/v1/asset-types", pump).status()).isEqualTo(201);
    }

    @Test
    void libraryInstallCopiesTypeIntoCompany() {
        assertThat(workerA.get("/api/v1/asset-types/library").status()).isEqualTo(403);
        List<Map<?, ?>> library = library(adminA);
        Map<?, ?> pump = entry(library, "lib_pump_unit");
        assertThat(pump.get("exists")).isEqualTo(false);
        assertThat(pump.get("category")).isEqualTo("primary");
        assertThat(pump.get("checklistCount")).isEqualTo(1);
        assertThat(entry(library, "lib_camp").get("exists")).as("такий код у компанії вже є").isEqualTo(true);
        assertThat(library).as("лише типи інших компаній, без назв компаній").allSatisfy(e -> assertThat(e.containsKey("companyId")).isFalse());

        Response installed = adminA.post("/api/v1/asset-types/library/" + pump.get("sourceId") + "/install", Map.of());
        assertThat(installed.status()).isEqualTo(201);
        assertThat(installed.str("code")).isEqualTo("lib_pump_unit");
        assertThat(((List<?>) installed.at("allowedChildTypes")).stream().map(Object::toString).toList())
                .as("лише коди, що є в компанії").containsExactly("lib_camp");
        assertThat((List<?>) installed.at("checklist")).hasSize(1);
        assertThat(entry(library(adminA), "lib_pump_unit").get("exists")).isEqualTo(true);
        assertThat(adminA.post("/api/v1/asset-types/library/" + pump.get("sourceId") + "/install", Map.of()).str("error"))
                .isEqualTo("code_exists");

        // Режим бібліотеки не протікає у звичайні запити
        assertThat(adminA.get("/api/v1/asset-types").list()).extracting(t -> (Object) ((Map<?, ?>) t).get("code"))
                .doesNotContain("lib_unknown");
        assertThat(adminA.get("/api/v1/asset-types/" + entry(library, "lib_unknown").get("sourceId")).status()).isEqualTo(404);
    }

    @Test
    void deleteOnlyWithoutActiveAssets() {
        String withAssets = adminA.post("/api/v1/asset-types", type("lib_trailer", "Trailer")).str("id");
        String asset = adminA.post("/api/v1/assets", Map.of("typeId", withAssets, "code", "TR-1", "attributes", Map.of())).str("id");
        Response blocked = adminA.delete("/api/v1/asset-types/" + withAssets);
        assertThat(blocked.status()).isEqualTo(409);
        assertThat(blocked.str("error")).isEqualTo("type_has_assets");
        assertThat(blocked.field("assets")).isEqualTo("1");

        adminA.post("/api/v1/assets/" + asset + "/archive", Map.of());
        assertThat(adminA.delete("/api/v1/asset-types/" + withAssets).status()).isEqualTo(204);
        assertThat(adminA.get("/api/v1/asset-types/" + withAssets).str("archived")).as("архівне обладнання — тип прихований")
                .isEqualTo("true");

        String empty = adminA.post("/api/v1/asset-types", type("lib_empty", "Empty")).str("id");
        assertThat(adminA.delete("/api/v1/asset-types/" + empty).status()).isEqualTo(204);
        assertThat(adminA.get("/api/v1/asset-types/" + empty).status()).as("без обладнання — видалено повністю").isEqualTo(404);
        assertThat(workerA.delete("/api/v1/asset-types/" + withAssets).status()).isEqualTo(403);
    }

    @Test
    void reorderTabs() {
        List<String> ids = new ArrayList<>(adminA.get("/api/v1/asset-types").list().stream()
                .map(t -> ((Map<?, ?>) t).get("id").toString()).toList());
        Collections.reverse(ids);
        Response reordered = adminA.put("/api/v1/asset-types/order", Map.of("ids", ids));
        assertThat(reordered.status()).isEqualTo(200);
        assertThat(adminA.get("/api/v1/asset-types").list().stream().map(t -> ((Map<?, ?>) t).get("id").toString()).toList())
                .containsExactlyElementsOf(ids);
        assertThat(adminA.put("/api/v1/asset-types/order", Map.of("ids", ids.subList(1, ids.size()))).field("ids"))
                .isEqualTo("not_all_types");
    }

    private static Map<String, Object> type(String code, String name) {
        return Map.of("code", code, "name", Map.of("en", name),
                "fields", List.of(),
                "statuses", List.of(Map.of("code", "active", "label", Map.of("en", "Active"))));
    }

    private static List<Map<?, ?>> library(TestApi api) {
        return api.get("/api/v1/asset-types/library").list().stream().<Map<?, ?>>map(e -> (Map<?, ?>) e).toList();
    }

    private static Map<?, ?> entry(List<Map<?, ?>> library, String code) {
        return library.stream().filter(e -> code.equals(e.get("code"))).findFirst().orElseThrow();
    }
}
