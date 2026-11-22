package com.safetics.reports;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.media.Schema;

/** Зведені звіти по обладнанню за періоди. Поля без @NotNull можуть бути null. */
public final class SummaryDtos {

    private SummaryDtos() {
    }

    public record CategoryMinutes(
            @NotNull @Schema(allowableValues = {"productive", "contractor_npt", "client_standby", "other"}) String category,
            @NotNull long minutes) {
    }

    public record CodeMinutes(
            @NotNull String code,
            @NotNull Map<String, String> name,
            @NotNull String category,
            @NotNull boolean billable,
            @NotNull long minutes) {
    }

    /** Метри, пробурені в діапазоні глибини [from, to). */
    public record DepthBand(
            @NotNull BigDecimal from,
            @NotNull BigDecimal to,
            @NotNull BigDecimal meters) {
    }

    public record UnitQuantity(
            @NotNull String unit,
            @NotNull BigDecimal quantity) {
    }

    public record ItemQuantity(
            @NotNull UUID itemId,
            @NotNull String code,
            @NotNull Map<String, String> name,
            @NotNull String unit,
            @NotNull BigDecimal quantity) {
    }

    /**
     * Обладнання × період (або «Разом», коли assetId порожній). reports*: подано / чернетка / не
     * заповнено. completedServices — послуги, завершені в періоді, на яких це обладнання працювало.
     */
    public record SummaryRow(
            UUID assetId,
            String assetCode,
            String assetName,
            LocalDate periodStart,
            LocalDate periodEnd,
            @NotNull long shifts,
            @NotNull long reportsSubmitted,
            @NotNull long reportsDraft,
            @NotNull long reportsMissing,
            @NotNull long billableMinutes,
            @NotNull long nonBillableMinutes,
            @NotNull List<CategoryMinutes> categories,
            @NotNull List<CodeMinutes> byCode,
            @NotNull BigDecimal meters,
            @NotNull BigDecimal coreLength,
            BigDecimal recovery,
            @NotNull List<DepthBand> depthBands,
            @NotNull List<UnitQuantity> quantities,
            @NotNull long completedServices,
            @NotNull List<ItemQuantity> consumables) {
    }

    public record SummaryView(
            @NotNull @Schema(allowableValues = {"month", "half_year", "year"}) String period,
            @NotNull LocalDate from,
            @NotNull LocalDate to,
            @NotNull int depthStep,
            @NotNull boolean includeDrafts,
            @NotNull List<SummaryRow> rows,
            @NotNull SummaryRow total) {
    }
}
