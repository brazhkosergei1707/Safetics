package com.safetics;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.json.JsonMapper;

/**
 * Простий HTTP-клієнт для інтеграційних тестів: JSON туди й назад, cookie сесії зберігаються
 * між запитами (як у браузері).
 */
public final class TestApi {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private final int port;
    private final Map<String, String> cookies = new LinkedHashMap<>();

    public TestApi(int port) {
        this.port = port;
    }

    /** Новий клієнт, увійшов як користувач з цим телефоном. */
    public static TestApi loggedIn(int port, String phone, String password) {
        TestApi api = new TestApi(port);
        Response r = api.post("/api/v1/auth/login", Map.of("phone", phone, "password", password));
        if (r.status() != 200) {
            throw new IllegalStateException("Login failed for " + phone + ": " + r.status() + " " + r.body());
        }
        return api;
    }

    public Response get(String path) {
        return send(request(path).GET().build());
    }

    public Response post(String path, Object body) {
        return send(request(path).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build());
    }

    public Response put(String path, Object body) {
        return send(request(path).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build());
    }

    public Response delete(String path) {
        return send(request(path).DELETE().build());
    }

    private HttpRequest.Builder request(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Accept", "application/json");
        if (!cookies.isEmpty()) {
            StringBuilder header = new StringBuilder();
            cookies.forEach((k, v) -> header.append(header.isEmpty() ? "" : "; ").append(k).append('=').append(v));
            builder.header("Cookie", header.toString());
        }
        return builder;
    }

    private Response send(HttpRequest request) {
        try {
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            for (String header : response.headers().allValues("set-cookie")) {
                String pair = header.split(";", 2)[0];
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    String value = pair.substring(eq + 1);
                    if (value.isEmpty()) {
                        cookies.remove(pair.substring(0, eq));
                    } else {
                        cookies.put(pair.substring(0, eq), value);
                    }
                }
            }
            String raw = response.body();
            Object body = raw == null || raw.isBlank() ? null : JSON.readValue(raw, Object.class);
            return new Response(response.statusCode(), body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public record Response(int status, Object body) {

        @SuppressWarnings("unchecked")
        public Map<String, Object> map() {
            return (Map<String, Object>) body;
        }

        @SuppressWarnings("unchecked")
        public List<Object> list() {
            return (List<Object>) body;
        }

        /** Значення за шляхом через крапку: "attributes.plannedDepth", "fields". */
        @SuppressWarnings("unchecked")
        public Object at(String path) {
            Object current = body;
            for (String part : path.split("\\.")) {
                current = current == null ? null : ((Map<String, Object>) current).get(part);
            }
            return current;
        }

        /** Код помилки поля з {"fields": {...}}; ключ може містити крапки: "shifts[0].start". */
        @SuppressWarnings("unchecked")
        public String field(String key) {
            Object fields = at("fields");
            Object value = fields == null ? null : ((Map<String, Object>) fields).get(key);
            return value == null ? null : value.toString();
        }

        public String str(String path) {
            Object value = at(path);
            return value == null ? null : value.toString();
        }
    }
}
