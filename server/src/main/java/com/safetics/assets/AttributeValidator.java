package com.safetics.assets;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Перевіряє і нормалізує значення полів об'єкта за схемою його типу.
 *
 * <p>Коди помилок для клієнта: {@code required}, {@code unknown_field}, {@code not_a_number},
 * {@code not_an_integer}, {@code not_a_boolean}, {@code invalid_date}, {@code invalid_option},
 * {@code too_small}, {@code too_large}, {@code too_long}.
 *
 * <p>Нормалізація: числа — BigDecimal, дати — рядок yyyy-MM-dd, порожні рядки — відсутнє значення.
 */
public final class AttributeValidator {

    static final int MAX_STRING = 500;
    static final int MAX_TEXT = 5000;

    private AttributeValidator() {
    }

    public record Result(Map<String, Object> values, Map<String, String> errors) {
        public boolean isValid() {
            return errors.isEmpty();
        }
    }

    public static Result validate(List<FieldDefinition> fields, Map<String, Object> input) {
        Map<String, Object> source = input == null ? Map.of() : input;
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, String> errors = new LinkedHashMap<>();

        Map<String, FieldDefinition> byKey = new LinkedHashMap<>();
        fields.forEach(f -> byKey.put(f.key(), f));

        for (String key : source.keySet()) {
            if (!byKey.containsKey(key)) {
                errors.put(key, "unknown_field");
            }
        }

        for (FieldDefinition field : fields) {
            Object raw = source.get(field.key());
            if (isEmpty(raw)) {
                if (field.isRequired()) {
                    errors.put(field.key(), "required");
                }
                continue;
            }
            try {
                values.put(field.key(), convert(field, raw));
            } catch (FieldError e) {
                errors.put(field.key(), e.getMessage());
            }
        }
        return new Result(values, errors);
    }

    private static boolean isEmpty(Object value) {
        return value == null || (value instanceof String s && s.isBlank());
    }

    private static Object convert(FieldDefinition field, Object raw) {
        return switch (field.type()) {
            case "string" -> text(raw, MAX_STRING);
            case "text" -> text(raw, MAX_TEXT);
            case "number" -> checkRange(field, number(raw));
            case "integer" -> {
                BigDecimal n = number(raw);
                if (n.stripTrailingZeros().scale() > 0) {
                    throw new FieldError("not_an_integer");
                }
                yield checkRange(field, n.setScale(0));
            }
            case "boolean" -> {
                if (raw instanceof Boolean b) {
                    yield b;
                }
                throw new FieldError("not_a_boolean");
            }
            case "date" -> {
                try {
                    yield LocalDate.parse(raw.toString().trim()).toString();
                } catch (DateTimeParseException e) {
                    throw new FieldError("invalid_date");
                }
            }
            case "enum" -> {
                String value = raw.toString().trim();
                List<String> options = field.options() == null ? List.of() : field.options();
                if (!options.contains(value)) {
                    throw new FieldError("invalid_option");
                }
                yield value;
            }
            default -> throw new FieldError("unsupported_type");
        };
    }

    private static String text(Object raw, int maxLength) {
        if (!(raw instanceof String) && !(raw instanceof Number)) {
            throw new FieldError("not_a_string");
        }
        String value = raw.toString().trim();
        if (value.length() > maxLength) {
            throw new FieldError("too_long");
        }
        return value;
    }

    private static BigDecimal number(Object raw) {
        if (raw instanceof Boolean) {
            throw new FieldError("not_a_number");
        }
        try {
            // Кома як десятковий роздільник — звична в Україні й Бразилії
            return new BigDecimal(raw.toString().trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            throw new FieldError("not_a_number");
        }
    }

    private static BigDecimal checkRange(FieldDefinition field, BigDecimal value) {
        if (field.min() != null && value.compareTo(field.min()) < 0) {
            throw new FieldError("too_small");
        }
        if (field.max() != null && value.compareTo(field.max()) > 0) {
            throw new FieldError("too_large");
        }
        return value;
    }

    private static final class FieldError extends RuntimeException {
        FieldError(String code) {
            super(code, null, false, false);
        }
    }
}
