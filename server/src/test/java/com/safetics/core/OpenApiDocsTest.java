package com.safetics.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.TestPropertySource;

import com.safetics.AbstractIntegrationTest;

import tools.jackson.databind.json.JsonMapper;

/**
 * Опис API, з якого генерується TypeScript-клієнт: ендпоінти на місці, а обов'язкові поля
 * позначені як required (інакше в TypeScript усе стало б необов'язковим).
 */
@TestPropertySource(properties = "springdoc.api-docs.enabled=true")
class OpenApiDocsTest extends AbstractIntegrationTest {

    @Value("${local.server.port}")
    int port;

    @Test
    @SuppressWarnings("unchecked")
    void apiDocsDescribeEndpointsAndRequiredFields() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v3/api-docs")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);

        Map<String, Object> docs = JsonMapper.builder().build().readValue(response.body(), Map.class);
        Map<String, Object> paths = (Map<String, Object>) docs.get("paths");
        assertThat(paths).containsKeys("/api/v1/auth/login", "/api/v1/auth/me", "/api/v1/audit-log");

        Map<String, Object> schemas = (Map<String, Object>) ((Map<String, Object>) docs.get("components")).get("schemas");
        Map<String, Object> session = (Map<String, Object>) schemas.get("SessionView");
        assertThat((List<String>) session.get("required"))
                .contains("user", "companies", "permissions")
                .doesNotContain("company");
    }
}
