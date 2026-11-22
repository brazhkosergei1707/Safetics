package com.safetics.core.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
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

import tools.jackson.databind.json.JsonMapper;

/**
 * Вхід через справжній HTTP: cookie для вебу, Bearer для бота, вибір компанії, оновлення токенів,
 * перевірка дозволу і ізоляція компаній уже на рівні API.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthApiTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";
    private static final String SINGLE_PHONE = "+380671000001";
    private static final String MULTI_PHONE = "+380671000002";

    @Value("${local.server.port}")
    int port;

    @Autowired
    PasswordEncoder passwordEncoder;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    UUID companyA;
    UUID companyB;

    /**
     * SINGLE: адмін A. MULTI: виконавець (worker) у A і адмін B.
     * Журнал: два записи в A, один у B.
     */
    @BeforeAll
    void insertFixtures() {
        JdbcClient admin = admin();
        companyA = admin.sql("insert into company (name) values ('Auth Co A') returning id").query(UUID.class).single();
        companyB = admin.sql("insert into company (name) values ('Auth Co B') returning id").query(UUID.class).single();

        String hash = passwordEncoder.encode(PASSWORD);
        UUID single = admin.sql("insert into app_user (phone, full_name, password_hash) values (?, 'Single', ?) returning id")
                .params(SINGLE_PHONE, hash).query(UUID.class).single();
        UUID multi = admin.sql("insert into app_user (phone, full_name, password_hash) values (?, 'Multi', ?) returning id")
                .params(MULTI_PHONE, hash).query(UUID.class).single();

        addMember(admin, companyA, single, "admin");
        addMember(admin, companyA, multi, "worker");
        addMember(admin, companyB, multi, "admin");

        for (UUID company : List.of(companyA, companyA, companyB)) {
            admin.sql("insert into audit_log (company_id, entity, entity_id, action) values (?, 'company', '1', 'create')")
                    .param(company).update();
        }
    }

    // ---------------------------------------------------------------- вхід

    @Test
    void wrongPasswordIsRejected() {
        Response r = post("/api/v1/auth/login", Map.of("phone", SINGLE_PHONE, "password", "wrong"), null, null);
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.map().get("error")).isEqualTo("invalid_credentials");
    }

    @Test
    void unknownPhoneIsRejectedTheSameWay() {
        Response r = post("/api/v1/auth/login", Map.of("phone", "+380990000000", "password", PASSWORD), null, null);
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.map().get("error")).isEqualTo("invalid_credentials");
    }

    @Test
    void singleCompanyLoginSetsHttpOnlyCookiesAndSelectsCompany() {
        Response r = login(SINGLE_PHONE);

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.setCookieHeaders()).anySatisfy(c -> assertThat(c).startsWith("sft_access=").contains("HttpOnly"));
        assertThat(r.setCookieHeaders()).anySatisfy(c -> assertThat(c).startsWith("sft_refresh=").contains("HttpOnly"));
        assertThat(map(r.map().get("company")).get("name")).isEqualTo("Auth Co A");
        assertThat(list(r.map().get("permissions"))).contains("audit.view", "stock.write_off");
    }

    @Test
    void phoneIsNormalized() {
        Response r = post("/api/v1/auth/login", Map.of("phone", "+380 (67) 100-00-01", "password", PASSWORD), null, null);
        assertThat(r.status()).isEqualTo(200);
    }

    @Test
    void protectedEndpointsRequireAuthentication() {
        assertThat(get("/api/v1/auth/me", null, null).status()).isEqualTo(401);
        assertThat(get("/api/v1/audit-log", null, null).status()).isEqualTo(401);
    }

    @Test
    void cookieSessionWorks() {
        Response login = login(SINGLE_PHONE);
        Response me = get("/api/v1/auth/me", login.cookies(), null);

        assertThat(me.status()).isEqualTo(200);
        assertThat(map(me.map().get("user")).get("fullName")).isEqualTo("Single");
        assertThat(map(me.map().get("company")).get("name")).isEqualTo("Auth Co A");
    }

    @Test
    void bearerTokenFlowForBotAndMobile() {
        Response token = post("/api/v1/auth/token", Map.of("phone", SINGLE_PHONE, "password", PASSWORD), null, null);
        assertThat(token.status()).isEqualTo(200);
        assertThat(token.setCookieHeaders()).isEmpty();

        String accessToken = (String) token.map().get("accessToken");
        Response me = get("/api/v1/auth/me", null, accessToken);
        assertThat(me.status()).isEqualTo(200);
        assertThat(map(me.map().get("company")).get("name")).isEqualTo("Auth Co A");
    }

    // ---------------------------------------------------------------- компанії і дозволи

    @Test
    void multiCompanyUserChoosesCompanyAndSeesOnlyItsData() {
        Response login = login(MULTI_PHONE);
        assertThat(login.status()).isEqualTo(200);
        assertThat(login.map().get("company")).isNull();
        assertThat(list(login.map().get("companies"))).hasSize(2);

        // Компанію не обрано — дозволів немає
        assertThat(get("/api/v1/audit-log", login.cookies(), null).status()).isEqualTo(403);

        // Адмін у B: бачить лише журнал B
        Response inB = post("/api/v1/auth/switch-company", Map.of("companyId", companyB.toString()), login.cookies(), null);
        assertThat(inB.status()).isEqualTo(200);
        assertThat(map(inB.map().get("company")).get("name")).isEqualTo("Auth Co B");
        Response auditB = get("/api/v1/audit-log", inB.cookies(), null);
        assertThat(auditB.status()).isEqualTo(200);
        assertThat(list(auditB.body())).hasSize(1);

        // Виконавець у A: журналу не бачить
        Response inA = post("/api/v1/auth/switch-company", Map.of("companyId", companyA.toString()), inB.cookies(), null);
        assertThat(inA.status()).isEqualTo(200);
        assertThat(list(inA.map().get("permissions"))).doesNotContain("audit.view");
        assertThat(get("/api/v1/audit-log", inA.cookies(), null).status()).isEqualTo(403);
    }

    @Test
    void adminSeesOnlyOwnCompanyAuditLog() {
        Response login = login(SINGLE_PHONE);
        Response audit = get("/api/v1/audit-log", login.cookies(), null);
        assertThat(audit.status()).isEqualTo(200);
        assertThat(list(audit.body())).hasSize(2);
    }

    @Test
    void cannotSwitchToForeignCompany() {
        Response login = login(SINGLE_PHONE);
        Response r = post("/api/v1/auth/switch-company", Map.of("companyId", companyB.toString()), login.cookies(), null);
        assertThat(r.status()).isEqualTo(403);
        assertThat(r.map().get("error")).isEqualTo("not_a_member");
    }

    // ---------------------------------------------------------------- оновлення і вихід

    @Test
    void refreshTokenIsSingleUse() {
        Response token = post("/api/v1/auth/token", Map.of("phone", SINGLE_PHONE, "password", PASSWORD), null, null);
        String refresh = (String) token.map().get("refreshToken");

        Response refreshed = post("/api/v1/auth/token/refresh", Map.of("refreshToken", refresh), null, null);
        assertThat(refreshed.status()).isEqualTo(200);
        assertThat(refreshed.map().get("refreshToken")).isNotEqualTo(refresh);
        assertThat(map(map(refreshed.map().get("session")).get("company")).get("name")).isEqualTo("Auth Co A");

        Response reused = post("/api/v1/auth/token/refresh", Map.of("refreshToken", refresh), null, null);
        assertThat(reused.status()).isEqualTo(401);
        assertThat(reused.map().get("error")).isEqualTo("invalid_refresh_token");
    }

    @Test
    void cookieRefreshAndLogout() {
        Response login = login(SINGLE_PHONE);

        Response refreshed = post("/api/v1/auth/refresh", Map.of(), login.cookies(), null);
        assertThat(refreshed.status()).isEqualTo(200);

        Response logout = post("/api/v1/auth/logout", Map.of(), refreshed.cookies(), null);
        assertThat(logout.status()).isEqualTo(204);

        Response afterLogout = post("/api/v1/auth/refresh", Map.of(), refreshed.cookies(), null);
        assertThat(afterLogout.status()).isEqualTo(401);
    }

    // ---------------------------------------------------------------- допоміжне

    private static void addMember(JdbcClient admin, UUID company, UUID user, String roleCode) {
        admin.sql("""
                        insert into membership (company_id, user_id, role_id)
                        select ?, ?, id from role where code = ? and company_id is null
                        """)
                .params(company, user, roleCode).update();
    }

    private Response login(String phone) {
        return post("/api/v1/auth/login", Map.of("phone", phone, "password", PASSWORD), null, null);
    }

    private Response get(String path, String cookies, String bearer) {
        return send(request(path, cookies, bearer).GET().build());
    }

    private Response post(String path, Map<String, ?> body, String cookies, String bearer) {
        return send(request(path, cookies, bearer)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build());
    }

    private HttpRequest.Builder request(String path, String cookies, String bearer) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Accept", "application/json");
        if (cookies != null && !cookies.isEmpty()) {
            builder.header("Cookie", cookies);
        }
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return builder;
    }

    private Response send(HttpRequest request) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            String raw = response.body();
            Object body = raw == null || raw.isBlank() ? null : json.readValue(raw, Object.class);
            return new Response(response.statusCode(), body, response.headers().allValues("set-cookie"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    record Response(int status, Object body, List<String> setCookieHeaders) {

        Map<String, Object> map() {
            return AuthApiTest.map(body);
        }

        /** Cookie з Set-Cookie у форматі заголовка Cookie; порожні (видалені) пропускаються. */
        String cookies() {
            Map<String, String> values = new LinkedHashMap<>();
            for (String header : setCookieHeaders) {
                String pair = header.split(";", 2)[0];
                int eq = pair.indexOf('=');
                if (eq > 0 && eq < pair.length() - 1) {
                    values.put(pair.substring(0, eq), pair.substring(eq + 1));
                }
            }
            StringBuilder sb = new StringBuilder();
            values.forEach((k, v) -> sb.append(sb.isEmpty() ? "" : "; ").append(k).append('=').append(v));
            return sb.toString();
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object value) {
        return (List<Object>) value;
    }
}
