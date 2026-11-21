package com.safetics.assets;

import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Статус об'єкта цього типу: «буриться», «не допущено».
 *
 * @param code  код (drilling)
 * @param label назва за мовами
 * @param color колір мітки в інтерфейсі (green, orange, red, blue, default)
 */
public record StatusDefinition(@NotBlank String code, @NotNull Map<String, String> label, String color) {
}
