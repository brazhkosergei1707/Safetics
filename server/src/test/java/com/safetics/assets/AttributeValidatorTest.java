package com.safetics.assets;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Перевірка значень полів за схемою типу. Без Spring і бази. */
class AttributeValidatorTest {

    private static final List<FieldDefinition> FIELDS = List.of(
            new FieldDefinition("depth", Map.of("en", "Depth"), "number", "m", true,
                    BigDecimal.ZERO, new BigDecimal("3000"), null),
            new FieldDefinition("year", Map.of(), "integer", null, null, null, null, null),
            new FieldDefinition("coreSize", Map.of(), "enum", null, null, null, null, List.of("NQ", "HQ")),
            new FieldDefinition("startDate", Map.of(), "date", null, null, null, null, null),
            new FieldDefinition("inspected", Map.of(), "boolean", null, null, null, null, null),
            new FieldDefinition("model", Map.of(), "string", null, null, null, null, null));

    @Test
    void validValuesAreNormalized() {
        Map<String, Object> input = new HashMap<>();
        input.put("depth", "120,5");
        input.put("year", 2018.0);
        input.put("coreSize", "HQ");
        input.put("startDate", "2026-11-01");
        input.put("inspected", true);
        input.put("model", "  LF90 ");

        AttributeValidator.Result result = AttributeValidator.validate(FIELDS, input);

        assertThat(result.isValid()).isTrue();
        assertThat(result.values().get("depth")).isEqualTo(new BigDecimal("120.5"));
        assertThat(result.values().get("year")).isEqualTo(new BigDecimal("2018"));
        assertThat(result.values().get("model")).isEqualTo("LF90");
    }

    @Test
    void invalidValuesGetFieldErrorCodes() {
        Map<String, Object> input = new HashMap<>();
        input.put("depth", -1);
        input.put("year", 2018.5);
        input.put("coreSize", "XX");
        input.put("startDate", "01.11.2026");
        input.put("inspected", "yes");
        input.put("unexpected", 1);

        Map<String, String> errors = AttributeValidator.validate(FIELDS, input).errors();

        assertThat(errors).containsEntry("depth", "too_small")
                .containsEntry("year", "not_an_integer")
                .containsEntry("coreSize", "invalid_option")
                .containsEntry("startDate", "invalid_date")
                .containsEntry("inspected", "not_a_boolean")
                .containsEntry("unexpected", "unknown_field");
    }

    @Test
    void requiredFieldCannotBeBlankOrMissing() {
        assertThat(AttributeValidator.validate(FIELDS, Map.of("depth", "  ")).errors()).containsEntry("depth", "required");
        assertThat(AttributeValidator.validate(FIELDS, null).errors()).containsEntry("depth", "required");
    }

    @Test
    void tooLargeAndNotANumber() {
        assertThat(AttributeValidator.validate(FIELDS, Map.of("depth", 3000.01)).errors()).containsEntry("depth", "too_large");
        assertThat(AttributeValidator.validate(FIELDS, Map.of("depth", "deep")).errors()).containsEntry("depth", "not_a_number");
    }
}
