package com.safetics.monthly;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.media.Schema;

/** Місячний звіт: проєкт × календарний місяць, версії, коригування, затвердження замовником. */
public final class MonthlyDtos {

    private MonthlyDtos() {
    }

    public record MetersLine(@NotNull String serviceCode, String coreSize, @NotNull BigDecimal meters, BigDecimal coreLength) {
    }

    public record HoursLine(@NotNull String code, @NotNull Map<String, String> name, @NotNull int minutes) {
    }

    public record QuantityLine(String serviceCode, String unit, @NotNull BigDecimal quantity) {
    }

    /** Бурова за місяць (основна частина: перевірені + включені менеджером). */
    public record AssetBlock(
            @NotNull UUID assetId,
            @NotNull String assetCode,
            @NotNull int reports,
            @NotNull BigDecimal meters,
            @NotNull List<MetersLine> metersByHole,
            @NotNull int billableMinutes,
            @NotNull List<HoursLine> hours,
            @NotNull List<QuantityLine> quantities) {
    }

    /** Рапорт, ще не перевірений замовником; included — менеджер включив в основну частину. */
    public record UnreviewedReport(
            @NotNull UUID shiftId,
            @NotNull LocalDate date,
            @NotNull String assetCode,
            @NotNull String shiftName,
            @NotNull String status,
            @NotNull BigDecimal meters,
            @NotNull int billableMinutes,
            @NotNull boolean included) {
    }

    public record AdjustmentView(
            @NotNull UUID id,
            @NotNull @Schema(allowableValues = {"meters", "hours", "quantity"}) String kind,
            UUID assetId,
            String assetCode,
            @NotNull BigDecimal amount,
            String unit,
            @NotNull String comment,
            UUID correctsShiftId,
            String correctsLabel,
            String createdByName,
            @NotNull OffsetDateTime createdAt) {
    }

    /** Разом за проєкт: основна частина + коригування. */
    public record MonthlyTotals(
            @NotNull BigDecimal meters,
            @NotNull int billableMinutes,
            @NotNull List<QuantityLine> quantities,
            @NotNull BigDecimal adjustmentMeters,
            @NotNull BigDecimal adjustmentHours) {
    }

    public record MonthlyContent(
            @NotNull List<AssetBlock> assets,
            @NotNull List<UnreviewedReport> unreviewed,
            @NotNull List<AdjustmentView> adjustments,
            @NotNull MonthlyTotals totals,
            @NotNull int acceptedCount,
            @NotNull int draftCount) {
    }

    /** status: draft → sent → approved (місяць закрито) / returned; superseded — замінено новою версією. */
    public record MonthlyReportView(
            @NotNull UUID id,
            @NotNull UUID projectId,
            @NotNull String projectCode,
            @NotNull String projectName,
            String customerName,
            @NotNull LocalDate month,
            @NotNull int version,
            @NotNull @Schema(allowableValues = {"draft", "sent", "approved", "returned", "superseded"}) String status,
            @NotNull MonthlyContent content,
            @NotNull OffsetDateTime generatedAt,
            String generatedByName,
            OffsetDateTime sentAt,
            OffsetDateTime decidedAt,
            String decidedByName,
            String decisionComment) {
    }

    /**
     * Місяць проєкту для підрядника: живий перегляд (з поточними коригуваннями й включеннями),
     * версії звіту, чи закрито. canManage — генерувати, надсилати, коригувати.
     */
    public record MonthView(
            @NotNull UUID projectId,
            @NotNull LocalDate month,
            @NotNull boolean closed,
            @NotNull MonthlyContent preview,
            @NotNull List<MonthlyReportView> versions,
            @NotNull boolean canManage,
            @NotNull List<ShiftOption> correctableShifts) {
    }

    /** Зміна закритого попереднього місяця — для коригувального запису. */
    public record ShiftOption(@NotNull UUID shiftId, @NotNull String label) {
    }

    public record InclusionRequest(@NotNull UUID shiftId, @NotNull Boolean included) {
    }

    public record AdjustmentRequest(
            @NotNull @Schema(allowableValues = {"meters", "hours", "quantity"}) String kind,
            UUID assetId,
            @NotNull BigDecimal amount,
            String unit,
            @NotBlank String comment,
            UUID correctsShiftId) {
    }

    public record MonthlyReturnRequest(@NotBlank String comment) {
    }
}
