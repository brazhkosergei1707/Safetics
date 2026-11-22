package com.safetics.projects;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Призначення людей і обладнання на проєкт. Дати включні; endDate == null — без дати завершення.
 * active — призначення діє сьогодні (за часовим поясом проєкту). userId — обліковий запис людини з
 * входом (щоб екран знав, що поточний користувач — супервайзер цього проєкту).
 */
public final class AssignmentDtos {

    private AssignmentDtos() {
    }

    public record PersonnelAssignmentView(
            @NotNull UUID id,
            @NotNull UUID projectId,
            @NotNull UUID personnelId,
            @NotNull String fullName,
            @NotNull String employmentType,
            String organization,
            @NotNull UUID positionId,
            @NotNull Map<String, String> positionName,
            @NotNull @Schema(allowableValues = {"supervisor", "storekeeper", "none"}) String projectRole,
            @NotNull boolean hasLogin,
            UUID userId,
            @NotNull LocalDate startDate,
            LocalDate endDate,
            @NotNull boolean active) {
    }

    /** positionId — посада на проєкті; якщо не вказана, береться посада виконавця. */
    public record PersonnelAssignmentRequest(
            @NotNull UUID personnelId,
            UUID positionId,
            @NotNull LocalDate startDate,
            LocalDate endDate) {
    }

    public record PersonnelAssignmentUpdateRequest(
            @NotNull UUID positionId,
            @NotNull LocalDate startDate,
            LocalDate endDate) {
    }

    /**
     * Супервайзер додає на свій проєкт людину без входу: новий виконавець + призначення.
     * Посада має бути позначена «може додавати супервайзер» (менеджерам — будь-яка).
     */
    public record CrewMemberRequest(
            @NotBlank String fullName,
            String phone,
            @Schema(allowableValues = {"staff", "subcontractor", "hired"}) String employmentType,
            String organization,
            @NotNull UUID positionId,
            @NotNull LocalDate startDate,
            LocalDate endDate) {
    }

    public record EquipmentAssignmentView(
            @NotNull UUID id,
            @NotNull UUID projectId,
            @NotNull UUID assetId,
            @NotNull String assetCode,
            String assetName,
            @NotNull String typeCode,
            @NotNull Map<String, String> typeName,
            @NotNull @Schema(allowableValues = {"primary", "auxiliary"}) String category,
            @NotNull LocalDate startDate,
            LocalDate endDate,
            @NotNull boolean active) {
    }

    public record EquipmentAssignmentRequest(
            @NotNull UUID assetId,
            @NotNull LocalDate startDate,
            LocalDate endDate) {
    }

    public record AssignmentDatesRequest(@NotNull LocalDate startDate, LocalDate endDate) {
    }
}
