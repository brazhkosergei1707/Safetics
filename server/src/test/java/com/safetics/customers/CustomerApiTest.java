package com.safetics.customers;

import static com.safetics.projects.ProjectFixtures.project;
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
import org.springframework.security.crypto.password.PasswordEncoder;

import com.safetics.AbstractIntegrationTest;
import com.safetics.TestApi;
import com.safetics.TestApi.Response;

/**
 * Замовники і контактні особи: створення, версії, архів, вхід у портал (новий і наявний обліковий
 * запис), відкликання входу, ізоляція компаній.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CustomerApiTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "customers-test-password";

    @Value("${local.server.port}")
    int port;

    @Autowired
    PasswordEncoder passwordEncoder;

    UUID companyX;
    TestApi managerX;
    TestApi adminY;

    @BeforeAll
    void setUp() {
        companyX = addCompany("Customers X");
        UUID companyY = addCompany("Customers Y");
        String hash = passwordEncoder.encode(PASSWORD);
        addMember(companyX, "+380674000001", hash, "head_manager");
        addMember(companyY, "+380674000002", hash, "admin");
        // Людина, що вже працює в Safetics (у компанії Y) і стане контактною особою замовника в X
        addMember(companyY, "+380674000009", hash, "worker");
        managerX = TestApi.loggedIn(port, "+380674000001", PASSWORD);
        adminY = TestApi.loggedIn(port, "+380674000002", PASSWORD);
    }

    @Test
    void createUpdateArchive() {
        Response created = managerX.post("/api/v1/customers", customer("Vale Test", "br"));
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.str("country")).isEqualTo("BR");
        String id = created.str("id");

        assertThat(managerX.post("/api/v1/customers", customer(" vale test ", null)).status())
                .as("назва унікальна без урахування регістру й пробілів").isEqualTo(409);
        assertThat(managerX.post("/api/v1/customers", customer("Bad Country", "Brazil")).field("country"))
                .isEqualTo("invalid_format");

        Map<String, Object> update = customer("Vale Test S.A.", "BR");
        assertThat(managerX.put("/api/v1/customers/" + id, update).field("version")).isEqualTo("required");
        update.put("version", 5);
        assertThat(managerX.put("/api/v1/customers/" + id, update).status()).isEqualTo(409);
        update.put("version", 1);
        assertThat(managerX.put("/api/v1/customers/" + id, update).str("name")).isEqualTo("Vale Test S.A.");

        assertThat(adminY.get("/api/v1/customers/" + id).status()).as("інша компанія не бачить").isEqualTo(404);

        // З активним проєктом архівувати не можна
        String projectId = managerX.post("/api/v1/projects", project("CUST-ARCH", id)).str("id");
        assertThat(managerX.post("/api/v1/customers/" + id + "/archive", Map.of()).status()).isEqualTo(409);
        managerX.post("/api/v1/projects/" + projectId + "/close", Map.of());
        assertThat(managerX.post("/api/v1/customers/" + id + "/archive", Map.of()).at("archived")).isEqualTo(true);
        assertThat(managerX.get("/api/v1/customers").list()).noneMatch(c -> id.equals(((Map<?, ?>) c).get("id")));
        assertThat(managerX.get("/api/v1/customers?includeArchived=true").list())
                .anyMatch(c -> id.equals(((Map<?, ?>) c).get("id")));
    }

    @Test
    void portalAccessForNewAndExistingAccounts() {
        String customerId = managerX.post("/api/v1/customers", customer("Portal Customer", "MR")).str("id");
        String otherCustomerId = managerX.post("/api/v1/customers", customer("Other Customer", "MR")).str("id");
        managerX.post("/api/v1/projects", project("PORTAL-1", customerId));
        managerX.post("/api/v1/projects", project("PORTAL-OTHER", otherCustomerId));

        // Новий обліковий запис: потрібен пароль
        String geologist = managerX.post("/api/v1/customers/" + customerId + "/contacts",
                contact("Geologist", "+380 67 400 00 10")).str("id");
        String path = "/api/v1/customer-contacts/" + geologist + "/portal-access";
        assertThat(managerX.post(path, Map.of("role", "client_rep")).field("password")).isEqualTo("required");
        assertThat(managerX.post(path, Map.of("role", "manager", "password", "portal-password")).field("role"))
                .as("лише ролі порталу").isEqualTo("invalid_option");
        Response granted = managerX.post(path, Map.of("role", "client_rep", "password", "portal-password"));
        assertThat(granted.status()).isEqualTo(200);
        assertThat(granted.at("newAccount")).isEqualTo(true);
        assertThat(granted.str("contact.portalRole")).isEqualTo("client_rep");
        assertThat(managerX.post(path, Map.of("role", "client_rep")).status()).isEqualTo(409);

        TestApi portal = TestApi.loggedIn(port, "+380674000010", "portal-password");
        assertThat(portal.get("/api/v1/projects").list())
                .as("представник замовника бачить лише проєкти свого замовника")
                .extracting(p -> (Object) ((Map<?, ?>) p).get("code")).containsExactly("PORTAL-1");
        assertThat(portal.get("/api/v1/customers").status()).as("список замовників — не для порталу").isEqualTo(403);

        // Наявний обліковий запис: пароль не потрібен і не змінюється
        String manager = managerX.post("/api/v1/customers/" + customerId + "/contacts",
                contact("Contract Manager", "+380674000009")).str("id");
        Response existing = managerX.post("/api/v1/customer-contacts/" + manager + "/portal-access",
                Map.of("role", "client_manager"));
        assertThat(existing.at("newAccount")).isEqualTo(false);
        Response existingLogin = new TestApi(port).post("/api/v1/auth/login",
                Map.of("phone", "+380674000009", "password", PASSWORD));
        assertThat(existingLogin.status()).as("старий пароль діє").isEqualTo(200);
        assertThat((List<?>) existingLogin.at("companies")).as("тепер у двох компаніях").hasSize(2);

        // Відкликання: проєкти зникають одразу, вхід у компанію більше не працює
        Response revoked = managerX.delete(path);
        assertThat(revoked.str("portalRole")).isNull();
        assertThat(portal.get("/api/v1/projects").list()).isEmpty();
        Response relogin = new TestApi(port).post("/api/v1/auth/login",
                Map.of("phone", "+380674000010", "password", "portal-password"));
        assertThat(relogin.str("company")).as("членство вимкнене — компанії немає").isNull();

        // Повторна видача відновлює вхід з новою роллю
        assertThat(managerX.post(path, Map.of("role", "client_manager")).str("contact.portalRole"))
                .isEqualTo("client_manager");
    }

    @Test
    void contactNeedsPhoneForPortalAndArchiveRevokesAccess() {
        String customerId = managerX.post("/api/v1/customers", customer("Archive Contacts", null)).str("id");
        Map<String, Object> noPhone = contact("No Phone", null);
        String id = managerX.post("/api/v1/customers/" + customerId + "/contacts", noPhone).str("id");
        assertThat(managerX.post("/api/v1/customer-contacts/" + id + "/portal-access",
                Map.of("role", "client_rep", "password", "portal-password")).field("phone")).isEqualTo("required");

        String withPhone = managerX.post("/api/v1/customers/" + customerId + "/contacts",
                contact("With Phone", "+380674000011")).str("id");
        managerX.post("/api/v1/customer-contacts/" + withPhone + "/portal-access",
                Map.of("role", "client_rep", "password", "portal-password"));
        Response archived = managerX.post("/api/v1/customer-contacts/" + withPhone + "/archive", Map.of());
        assertThat(archived.at("archived")).isEqualTo(true);
        assertThat(archived.str("portalRole")).isNull();
        assertThat(managerX.get("/api/v1/customers/" + customerId + "/contacts").list()).hasSize(1);
    }

    private static Map<String, Object> customer(String name, String country) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("country", country);
        return body;
    }

    private static Map<String, Object> contact(String fullName, String phone) {
        Map<String, Object> body = new HashMap<>();
        body.put("fullName", fullName);
        body.put("jobTitle", "Geólogo");
        body.put("phone", phone);
        return body;
    }
}
