package com.safetics.assets;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Поле типу об'єкта: «Проєктна глибина, м, число ≥ 0».
 *
 * @param key      ключ в attributes: латиниця, цифри, _ (plannedDepth)
 * @param label    назва за мовами: {"en": "Planned depth", "uk": "Проєктна глибина"}
 * @param type     тип значення
 * @param unit     одиниця для показу (m, °, h, km) або null
 * @param required чи обов'язкове
 * @param min      мінімум для number/integer
 * @param max      максимум для number/integer
 * @param options  варіанти для enum (NQ, HQ, PQ)
 */
public record FieldDefinition(
        @NotBlank String key,
        @NotNull Map<String, String> label,
        @NotBlank @Schema(allowableValues = {"string", "text", "number", "integer", "boolean", "date", "enum"}) String type,
        String unit,
        Boolean required,
        BigDecimal min,
        BigDecimal max,
        List<String> options) {

    public static final List<String> TYPES = List.of("string", "text", "number", "integer", "boolean", "date", "enum");

    public boolean isRequired() {
        return Boolean.TRUE.equals(required);
    }
}
