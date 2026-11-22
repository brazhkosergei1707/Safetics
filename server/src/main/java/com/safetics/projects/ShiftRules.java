package com.safetics.projects;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.safetics.core.web.ApiException;
import com.safetics.projects.ProjectDtos.Shift;

/**
 * Перевірка графіка змін: 1–6 змін, коди унікальні, тривалість 30 хв – 24 год, зміни за добу не
 * перекриваються (з урахуванням переходу через північ), сума — не більше 24 год.
 * Приклади: 2×12 (07:00, 19:00), 3×8, 1×8, 1×12. Повертає зміни, відсортовані за часом початку.
 */
final class ShiftRules {

    static final int DAY_MINUTES = 24 * 60;
    static final int MAX_SHIFTS = 6;

    private ShiftRules() {
    }

    static List<Shift> validate(List<Shift> input) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (input == null || input.isEmpty()) {
            throw ApiException.validation("shifts", "required");
        }
        if (input.size() > MAX_SHIFTS) {
            throw ApiException.validation("shifts", "too_many");
        }

        record Parsed(Shift shift, int startMinute, int index) {
        }
        List<Parsed> parsed = new ArrayList<>();
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < input.size(); i++) {
            Shift s = input.get(i);
            String prefix = "shifts[" + i + "].";
            String code = s.code() == null ? "" : s.code().trim();
            if (!code.matches("[a-z0-9_]{1,20}")) {
                errors.put(prefix + "code", "invalid_format");
            } else if (!codes.add(code)) {
                errors.put(prefix + "code", "duplicate");
            }
            if (s.name() == null || s.name().isBlank()) {
                errors.put(prefix + "name", "required");
            }
            Integer minutes = s.durationMinutes();
            if (minutes == null) {
                errors.put(prefix + "durationMinutes", "required");
            } else if (minutes < 30) {
                errors.put(prefix + "durationMinutes", "too_small");
            } else if (minutes > DAY_MINUTES) {
                errors.put(prefix + "durationMinutes", "too_large");
            }
            LocalTime start = null;
            try {
                if (s.start() != null && s.start().trim().matches("\\d{2}:\\d{2}")) {
                    start = LocalTime.parse(s.start().trim());
                }
            } catch (DateTimeParseException ignored) {
                // нижче — invalid_format
            }
            if (start == null) {
                errors.put(prefix + "start", "invalid_format");
            }
            if (start != null && minutes != null) {
                parsed.add(new Parsed(new Shift(code, s.name() == null ? null : s.name().trim(),
                        start.toString(), minutes), start.getHour() * 60 + start.getMinute(), i));
            }
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }

        parsed.sort(Comparator.comparingInt(Parsed::startMinute));
        int total = parsed.stream().mapToInt(p -> p.shift().durationMinutes()).sum();
        if (total > DAY_MINUTES) {
            throw ApiException.validation("shifts", "exceeds_day");
        }
        // Кінець кожної зміни — не пізніше початку наступної; для останньої — початку першої завтра
        for (int i = 0; i < parsed.size(); i++) {
            Parsed current = parsed.get(i);
            Parsed next = parsed.get((i + 1) % parsed.size());
            int nextStart = next.startMinute() + (i + 1 == parsed.size() ? DAY_MINUTES : 0);
            if (parsed.size() > 1 && current.startMinute() + current.shift().durationMinutes() > nextStart) {
                throw ApiException.validation("shifts[" + next.index() + "].start", "overlap");
            }
        }
        return parsed.stream().map(Parsed::shift).toList();
    }
}
