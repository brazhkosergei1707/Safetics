package com.safetics.core.web;

import java.util.Map;

import org.springframework.http.HttpStatus;

/**
 * Помилка API з кодом для клієнта. Перетворюється на JSON у {@link ApiExceptionHandler}:
 * {@code {"error": "validation_failed", "fields": {"plannedDepth": "too_small"}}}.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, String> fields;

    public ApiException(HttpStatus status, String code, Map<String, String> fields) {
        super(code + (fields.isEmpty() ? "" : " " + fields));
        this.status = status;
        this.code = code;
        this.fields = Map.copyOf(fields);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Map<String, String> fields() {
        return fields;
    }

    public static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not_found", Map.of());
    }

    public static ApiException conflict(String code) {
        return new ApiException(HttpStatus.CONFLICT, code, Map.of());
    }

    /** Помилки окремих полів: ключ — поле, значення — код (required, too_small, invalid_option…). */
    public static ApiException validation(Map<String, String> fields) {
        return new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", fields);
    }

    public static ApiException validation(String field, String code) {
        return validation(Map.of(field, code));
    }
}
