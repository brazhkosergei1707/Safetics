package com.safetics.reports;

import java.util.Map;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.media.Schema;

/** Довідники рапорту: коди операцій і позиції витрат. Поля без @NotNull можуть бути null. */
public final class ReportCatalogDtos {

    private ReportCatalogDtos() {
    }

    /**
     * category: productive — продуктивний час, contractor_npt — непродуктивний з вини підрядника,
     * client_standby — простій з вини замовника, other — інше. billable — оплачуваний. drilling — «Буріння»:
     * рядок журналу з цим кодом має задачу свердловини й інтервал (глибина від / до, керн).
     */
    public record ActivityCodeView(
            @NotNull UUID id,
            @NotNull String code,
            @NotNull Map<String, String> name,
            @NotNull @Schema(allowableValues = {"productive", "contractor_npt", "client_standby", "other"}) String category,
            @NotNull boolean billable,
            @NotNull boolean drilling,
            @NotNull int sortOrder,
            String templateKey,
            @NotNull boolean archived) {
    }

    /**
     * billable порожній — за категорією (продуктивний і простій замовника — так); drilling порожній — ні.
     * code після створення не змінюється.
     */
    public record ActivityCodeRequest(
            @NotBlank String code,
            @NotNull Map<String, String> name,
            @NotBlank @Schema(allowableValues = {"productive", "contractor_npt", "client_standby", "other"}) String category,
            Boolean billable,
            Integer sortOrder,
            Boolean drilling) {
    }

    /** serialTracked — облік за серійним номером (коронки, розширювачі). */
    public record ConsumableItemView(
            @NotNull UUID id,
            @NotNull String code,
            @NotNull Map<String, String> name,
            @NotNull String unit,
            @NotNull boolean serialTracked,
            String templateKey,
            @NotNull boolean archived) {
    }

    public record ConsumableItemRequest(
            @NotBlank String code,
            @NotNull Map<String, String> name,
            @NotBlank String unit,
            Boolean serialTracked) {
    }
}
