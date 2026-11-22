package com.safetics.projects;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Тіла запитів для тестів проєктів. */
public final class ProjectFixtures {

    private ProjectFixtures() {
    }

    public static Map<String, Object> shift(String code, String start, int minutes) {
        return Map.of("code", code, "name", code.toUpperCase(), "start", start, "durationMinutes", minutes);
    }

    /** Графік 2×12: 07:00 і 19:00. */
    public static List<Map<String, Object>> twoByTwelve() {
        return List.of(shift("day", "07:00", 720), shift("night", "19:00", 720));
    }

    public static Map<String, Object> project(String code, String customerId) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("name", "Project " + code);
        body.put("customerId", customerId);
        body.put("country", "br");
        body.put("timeZone", "America/Belem");
        body.put("startDate", LocalDate.now().minusDays(30).toString());
        body.put("shifts", twoByTwelve());
        return body;
    }
}
