package com.safetics.shifts;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import com.safetics.shifts.ShiftDtos.CrewMemberView;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Змінний рапорт. Час журналу — хвилини від початку зміни [startMinute, endMinute). Поля без
 * @NotNull можуть бути null.
 */
public final class ShiftReportDtos {

    private ShiftReportDtos() {
    }

    public record TimeEntryView(
            @NotNull int startMinute,
            @NotNull int endMinute,
            @NotNull UUID activityCodeId,
            @NotNull String activityCode,
            @NotNull Map<String, String> activityName,
            @NotNull String category,
            @NotNull boolean billable,
            UUID taskId,
            Long taskNumber,
            String taskTitle,
            String comment) {
    }

    /** meters = до − від; recovery — вихід керна, %. */
    public record IntervalView(
            @NotNull UUID serviceId,
            @NotNull String serviceCode,
            @NotNull BigDecimal depthFrom,
            @NotNull BigDecimal depthTo,
            @NotNull BigDecimal meters,
            String coreSize,
            BigDecimal coreLength,
            BigDecimal recovery,
            String comment) {
    }

    public record QuantityView(
            @NotNull UUID serviceId,
            @NotNull String serviceCode,
            String unit,
            @NotNull BigDecimal quantity,
            String comment) {
    }

    public record ConsumableView(
            @NotNull UUID itemId,
            @NotNull String itemCode,
            @NotNull Map<String, String> itemName,
            @NotNull String unit,
            @NotNull BigDecimal quantity,
            String comment) {
    }

    /** previousEnd — значення на кінець попередньої зміни (для заповнення й попередження). */
    public record MeterView(
            @NotNull UUID assetId,
            @NotNull String assetCode,
            BigDecimal startValue,
            BigDecimal endValue,
            BigDecimal previousEnd) {
    }

    /**
     * Помилка (блокує подання) або попередження. code: time_empty, time_gap, time_overlap,
     * time_total, depth_gap, core_over_100, meter_mismatch, interval_without_time.
     */
    public record ReportIssue(
            @NotNull String code,
            @NotNull Map<String, String> params) {
    }

    public record CodeTotal(
            @NotNull String activityCode,
            @NotNull Map<String, String> name,
            @NotNull String category,
            @NotNull boolean billable,
            @NotNull int minutes) {
    }

    public record ReportTotals(
            @NotNull int billableMinutes,
            @NotNull int nonBillableMinutes,
            @NotNull List<CodeTotal> byCode,
            @NotNull BigDecimal meters,
            @NotNull BigDecimal coreLength,
            BigDecimal recovery) {
    }

    /** Початок інтервалу для нового рапорту: свердловина й останнє «до» з попередніх рапортів. */
    public record IntervalStart(
            @NotNull UUID serviceId,
            @NotNull String serviceCode,
            @NotNull BigDecimal depthFrom) {
    }

    /** Задача проєкту для рядка журналу часу (буріння свердловини, переїзд, ТО…). */
    public record ReportTaskOption(
            @NotNull UUID id,
            @NotNull long number,
            @NotNull String title,
            @NotNull String statusCategory) {
    }

    /** Подія рапорту: подано, повернуто / виправлено / перевірено замовником, погоджено, повернуто в чернетку. */
    public record ReportEventView(
            @NotNull @Schema(allowableValues = {"submitted", "returned", "corrected", "agreed", "accepted", "reopened"})
            String kind,
            String comment,
            String authorName,
            @NotNull OffsetDateTime createdAt) {
    }

    /** Правка замовника: запропоновані журнал часу, інтервали й обсяги (рапорт не змінено, доки підрядник не погодиться). */
    public record CorrectionView(
            @NotNull String comment,
            String authorName,
            @NotNull OffsetDateTime createdAt,
            @NotNull List<TimeEntryView> times,
            @NotNull List<IntervalView> intervals,
            @NotNull List<QuantityView> quantities,
            @NotNull ReportTotals totals) {
    }

    /**
     * status: new — ще не збережено (поля заповнені з попередньої зміни), draft — чернетка,
     * submitted — подано, returned — повернуто замовником, corrected — виправлено замовником (чекає
     * згоди), accepted — перевірено. reportTaskId — задача «Змінний рапорт» зміни. Для порталу
     * витрати й лічильники порожні.
     */
    public record ShiftReportView(
            @NotNull UUID shiftId,
            UUID reportId,
            @NotNull @Schema(allowableValues = {"new", "draft", "submitted", "returned", "corrected", "accepted"}) String status,
            @NotNull LocalDate date,
            @NotNull OffsetDateTime startsAt,
            @NotNull int durationMinutes,
            String notes,
            @NotNull List<TimeEntryView> times,
            @NotNull List<IntervalView> intervals,
            @NotNull List<QuantityView> quantities,
            @NotNull List<ConsumableView> consumables,
            @NotNull List<MeterView> meters,
            @NotNull ReportTotals totals,
            @NotNull List<ReportIssue> errors,
            @NotNull List<ReportIssue> warnings,
            @NotNull List<IntervalStart> prefill,
            @NotNull boolean canEdit,
            @NotNull boolean canReopen,
            UUID reportTaskId,
            OffsetDateTime submittedAt,
            String submittedByName,
            @NotNull int version,
            OffsetDateTime acceptedAt,
            @NotNull List<ReportEventView> events,
            CorrectionView correction,
            @NotNull boolean canAgree,
            @NotNull List<ReportTaskOption> taskOptions) {
    }

    public record TimeEntryRequest(
            @NotNull Integer startMinute,
            @NotNull Integer endMinute,
            @NotNull UUID activityCodeId,
            UUID taskId,
            String comment) {
    }

    public record IntervalRequest(
            @NotNull UUID serviceId,
            @NotNull BigDecimal depthFrom,
            @NotNull BigDecimal depthTo,
            String coreSize,
            BigDecimal coreLength,
            String comment) {
    }

    public record QuantityRequest(
            @NotNull UUID serviceId,
            @NotNull BigDecimal quantity,
            String comment) {
    }

    public record ConsumableRequest(
            @NotNull UUID itemId,
            @NotNull BigDecimal quantity,
            String comment) {
    }

    public record MeterRequest(
            @NotNull UUID assetId,
            @NotNull BigDecimal startValue,
            @NotNull BigDecimal endValue) {
    }

    /** version: 0 — новий рапорт. */
    public record ShiftReportRequest(
            String notes,
            @NotNull @Valid List<TimeEntryRequest> times,
            @NotNull @Valid List<IntervalRequest> intervals,
            @NotNull @Valid List<QuantityRequest> quantities,
            @NotNull @Valid List<ConsumableRequest> consumables,
            @NotNull @Valid List<MeterRequest> meters,
            @NotNull Integer version) {
    }

    public record ReportActionRequest(@NotNull Integer version) {
    }

    /** Повернути рапорт: коментар обов'язковий. */
    public record ReportReturnRequest(@NotNull Integer version, @NotBlank String comment) {
    }

    /** Правка замовника: коментар обов'язковий; витрати й лічильники лишаються підрядника. */
    public record ReportCorrectionRequest(
            @NotNull Integer version,
            @NotBlank String comment,
            @NotNull @Valid List<TimeEntryRequest> times,
            @NotNull @Valid List<IntervalRequest> intervals,
            @NotNull @Valid List<QuantityRequest> quantities) {
    }

    /** Рядок списку «Рапорти на перевірку» (портал). */
    public record ReviewListItem(
            @NotNull UUID shiftId,
            @NotNull UUID projectId,
            @NotNull String projectCode,
            @NotNull String assetCode,
            @NotNull LocalDate date,
            @NotNull String shiftName,
            @NotNull String status,
            OffsetDateTime submittedAt,
            @NotNull BigDecimal meters,
            @NotNull int billableMinutes) {
    }

    /** Рапорт для замовника: без витрат і лічильників; canReview — можна прийняти / повернути / виправити. */
    public record ReviewReportView(
            @NotNull ShiftReportView report,
            @NotNull UUID projectId,
            @NotNull String projectCode,
            @NotNull String projectName,
            @NotNull String assetCode,
            @NotNull String shiftName,
            @NotNull List<CrewMemberView> crew,
            @NotNull boolean canReview) {
    }

    /** Зміни за період за графіком (не пізніше сьогодні, до 62 днів); assetId порожній — усе основне обладнання. */
    public record GenerateShiftsRequest(
            @NotNull LocalDate from,
            @NotNull LocalDate to,
            UUID assetId) {
    }

    public record GenerateShiftsResponse(@NotNull int created) {
    }
}
