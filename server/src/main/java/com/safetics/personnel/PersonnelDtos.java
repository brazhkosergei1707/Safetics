package com.safetics.personnel;

import java.util.Map;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.media.Schema;

/** Запити й відповіді API посад і виконавців. Поля без @NotNull можуть бути null. */
public final class PersonnelDtos {

    private PersonnelDtos() {
    }

    /**
     * projectRole — роль на проєкті, яку дає посада (supervisor — супервайзер проєкту, storekeeper —
     * комірник). supervisorCanAdd — супервайзер може сам додати людину без входу з цією посадою.
     */
    public record PositionView(
            @NotNull UUID id,
            @NotNull String code,
            @NotNull Map<String, String> name,
            @NotNull @Schema(allowableValues = {"supervisor", "storekeeper", "none"}) String projectRole,
            @NotNull boolean supervisorCanAdd,
            @NotNull boolean archived) {
    }

    /** code після створення не змінюється. */
    public record PositionRequest(
            @NotBlank String code,
            @NotNull Map<String, String> name,
            @Schema(allowableValues = {"supervisor", "storekeeper", "none"}) String projectRole,
            Boolean supervisorCanAdd) {
    }

    /**
     * employmentType: staff — штатний, subcontractor — субпідрядник, hired — найманий (водій, кухар).
     * loginRole — роль входу в систему, якщо вхід є й активний.
     */
    public record PersonnelView(
            @NotNull UUID id,
            @NotNull String fullName,
            String phone,
            @NotNull @Schema(allowableValues = {"staff", "subcontractor", "hired"}) String employmentType,
            String organization,
            UUID positionId,
            UUID userId,
            String loginRole,
            String notes,
            @NotNull int version,
            @NotNull boolean archived) {
    }

    /** version обов'язкова при зміні. employmentType за замовчуванням staff. */
    public record PersonnelRequest(
            @NotBlank String fullName,
            String phone,
            @Schema(allowableValues = {"staff", "subcontractor", "hired"}) String employmentType,
            String organization,
            UUID positionId,
            String notes,
            Integer version) {
    }

    /**
     * Вхід у систему за телефоном виконавця. role — системна роль (не ширша за вашу); password —
     * лише для нового облікового запису. Якщо людина вже працює в компанії, її роль не змінюється.
     */
    public record PersonnelLoginRequest(@NotBlank String role, String password) {
    }

    public record PersonnelLoginResponse(@NotNull PersonnelView personnel, @NotNull boolean newAccount) {
    }
}
