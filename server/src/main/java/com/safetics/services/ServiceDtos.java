package com.safetics.services;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import com.safetics.assets.FieldDefinition;

import io.swagger.v3.oas.annotations.media.Schema;

/** Запити й відповіді API типів послуг і послуг. Поля без @NotNull можуть бути null. */
public final class ServiceDtos {

    private ServiceDtos() {
    }

    public record ServiceTypeView(
            @NotNull UUID id,
            @NotNull String code,
            @NotNull Map<String, String> name,
            @NotNull List<FieldDefinition> fields,
            @NotNull UUID taskTypeId,
            String templateKey,
            @NotNull boolean archived) {
    }

    /**
     * code після створення не змінюється. taskTypeId — тип головної задачі (предмет — послуга); порожній
     * при створенні — створюється новий тип «Виконати …», при зміні — лишається поточний.
     */
    public record ServiceTypeRequest(
            @NotBlank String code,
            @NotNull Map<String, String> name,
            @NotNull @Valid List<FieldDefinition> fields,
            UUID taskTypeId) {
    }

    /**
     * status: planned — заплановано, in_progress — в роботі, completed — завершено, cancelled — скасовано.
     * customerId / customerName — замовник послуги: власний (постійний проєкт) або замовник проєкту.
     * mainTask* і assignee* — головна задача послуги та її виконавець (відповідальний); порожні для
     * того, хто задач не бачить (портал замовника).
     */
    public record ServiceView(
            @NotNull UUID id,
            @NotNull UUID projectId,
            @NotNull UUID typeId,
            @NotNull String typeCode,
            @NotNull String code,
            String name,
            UUID customerId,
            String customerName,
            @NotNull @Schema(allowableValues = {"planned", "in_progress", "completed", "cancelled"}) String status,
            @NotNull Map<String, Object> attributes,
            LocalDate plannedStart,
            LocalDate plannedEnd,
            OffsetDateTime actualStart,
            OffsetDateTime actualEnd,
            String cancelReason,
            UUID mainTaskId,
            Long mainTaskNumber,
            UUID mainTaskTypeId,
            UUID assigneeId,
            String assigneeName,
            @NotNull int version,
            @NotNull OffsetDateTime createdAt) {
    }

    /**
     * customerId — лише для проєкту без замовника. assigneeId — відповідальний (виконавець головної
     * задачі); порожній — супервайзер проєкту. Замовник відповідального не обирає.
     */
    public record ServiceCreateRequest(
            @NotNull UUID typeId,
            @NotBlank String code,
            String name,
            UUID customerId,
            @NotNull Map<String, Object> attributes,
            LocalDate plannedStart,
            LocalDate plannedEnd,
            UUID assigneeId) {
    }

    /** assigneeId змінює виконавця головної задачі (поки вона відкрита); замовник його не змінює. */
    public record ServiceUpdateRequest(
            @NotBlank String code,
            String name,
            UUID customerId,
            @NotNull Map<String, Object> attributes,
            LocalDate plannedStart,
            LocalDate plannedEnd,
            UUID assigneeId,
            @NotNull Integer version) {
    }

    /** Значення форми закриття головної задачі (фактична глибина тощо). */
    public record CompleteServiceRequest(Map<String, Object> closingValues) {
    }

    public record CancelServiceRequest(@NotBlank String reason) {
    }
}
