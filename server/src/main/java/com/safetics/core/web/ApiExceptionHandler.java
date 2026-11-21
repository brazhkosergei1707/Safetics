package com.safetics.core.web;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.validation.constraints.NotNull;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Єдиний формат помилок API: {@code {"error": "код", "fields": {"поле": "код"}}}. */
@RestControllerAdvice
public class ApiExceptionHandler {

    public record ApiErrorResponse(@NotNull String error, @NotNull Map<String, String> fields) {
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiErrorResponse> handle(ApiException e) {
        return ResponseEntity.status(e.status()).body(new ApiErrorResponse(e.code(), e.fields()));
    }

    /** Порушення @NotBlank, @NotNull тощо в тілі запиту. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handle(MethodArgumentNotValidException e) {
        Map<String, String> fields = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(error ->
                fields.putIfAbsent(error.getField(), codeOf(error.getCode())));
        return ResponseEntity.badRequest().body(new ApiErrorResponse("validation_failed", fields));
    }

    /** Унікальний ключ у базі (наприклад, номер свердловини вже є). */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ApiErrorResponse> handle(DuplicateKeyException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiErrorResponse("duplicate", Map.of()));
    }

    private static String codeOf(String constraint) {
        if (constraint == null) {
            return "invalid";
        }
        return switch (constraint) {
            case "NotBlank", "NotNull", "NotEmpty" -> "required";
            case "Size" -> "invalid_length";
            case "Pattern" -> "invalid_format";
            default -> "invalid";
        };
    }
}
