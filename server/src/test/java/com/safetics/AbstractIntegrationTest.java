package com.safetics;

import java.util.UUID;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Спільна основа інтеграційних тестів: один PostgreSQL у Docker на всі тести і один контекст Spring
 * (кешується, бо конфігурація однакова). Застосунок працює під роллю safetics_app без BYPASSRLS,
 * фікстури вставляє суперкористувач через {@link #admin()}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    protected static final String APP_DB_USER = "safetics_app";
    protected static final String APP_DB_PASSWORD = "test_app_password";

    /** 48 байт у Base64 — лише для тестів. */
    private static final String TEST_JWT_SECRET = "dGVzdC1vbmx5LWp3dC1zZWNyZXQtZm9yLXNhZmV0aWNzLWludGVncmF0aW9uLXRlc3Rz";

    /**
     * Стартує в статичному блоці, а не через @Container: при PER_CLASS Spring піднімає контекст
     * раніше, ніж розширення Testcontainers встигає запустити контейнер. Зупиняє його Ryuk
     * після завершення JVM.
     */
    protected static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    static {
        postgres.start();
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_DB_USER);
        registry.add("spring.datasource.password", () -> APP_DB_PASSWORD);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("spring.flyway.placeholders.appdbuser", () -> APP_DB_USER);
        registry.add("spring.flyway.placeholders.appdbpassword", () -> APP_DB_PASSWORD);
        registry.add("safetics.security.jwt-secret", () -> TEST_JWT_SECRET);
        registry.add("safetics.security.secure-cookies", () -> "false");
    }

    /** Суперкористувач бази: оминає RLS, лише для підготовки даних у тестах. */
    protected static JdbcClient admin() {
        return JdbcClient.create(
                new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
    }

    /** Користувач з паролем (хеш BCrypt) і членством із системною роллю. */
    protected static UUID addMember(UUID company, String phone, String passwordHash, String role) {
        JdbcClient admin = admin();
        UUID user = admin.sql("insert into app_user (phone, full_name, password_hash) values (?, ?, ?) returning id")
                .params(phone, "User " + phone, passwordHash).query(UUID.class).single();
        admin.sql("insert into membership (company_id, user_id, role_id) select ?, ?, id from role where code = ? and company_id is null")
                .params(company, user, role).update();
        return user;
    }

    protected static UUID addCompany(String name) {
        return admin().sql("insert into company (name) values (?) returning id").param(name).query(UUID.class).single();
    }
}
