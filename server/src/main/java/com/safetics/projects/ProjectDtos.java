package com.safetics.projects;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.media.Schema;

/** Запити й відповіді API проєктів, графіка змін і доступу. Поля без @NotNull можуть бути null. */
public final class ProjectDtos {

    private ProjectDtos() {
    }

    /**
     * Зміна графіка. start — час початку «HH:mm» у часовому поясі проєкту; зміна може переходити
     * через північ (датою рапорту тоді є дата її початку).
     */
    public record Shift(
            @NotBlank @Schema(description = "Код зміни: day, night, a, b…", pattern = "^[a-z0-9_]{1,20}$") String code,
            @NotBlank String name,
            @NotBlank @Schema(example = "07:00") String start,
            @NotNull @Schema(minimum = "30", maximum = "1440") Integer durationMinutes) {
    }

    /**
     * shifts — графік, що діє сьогодні (за часовим поясом проєкту).
     * status: active — триває, closed — завершено.
     */
    public record ProjectView(
            @NotNull UUID id,
            @NotNull String code,
            @NotNull String name,
            UUID customerId,
            String customerName,
            String location,
            String country,
            @NotNull String timeZone,
            @NotNull LocalDate startDate,
            LocalDate endDate,
            @NotNull @Schema(allowableValues = {"active", "closed"}) String status,
            @NotNull List<Shift> shifts,
            UUID managerId,
            String managerName,
            @NotNull int version) {
    }

    /**
     * shifts — перший графік змін; діє з дати початку проєкту. managerId — відповідальний менеджер
     * (отримує доступ до проєкту).
     */
    public record ProjectCreateRequest(
            @NotBlank String code,
            @NotBlank String name,
            UUID customerId,
            String location,
            String country,
            @NotBlank @Schema(example = "America/Belem") String timeZone,
            @NotNull LocalDate startDate,
            LocalDate endDate,
            @NotNull @Valid List<Shift> shifts,
            UUID managerId) {
    }

    /** managerId змінює лише той, хто має project.create; для решти лишається поточний. */
    public record ProjectUpdateRequest(
            @NotBlank String code,
            @NotBlank String name,
            UUID customerId,
            String location,
            String country,
            @NotBlank String timeZone,
            @NotNull LocalDate startDate,
            LocalDate endDate,
            UUID managerId,
            @NotNull Integer version) {
    }

    /**
     * Версія графіка діє з validFrom до наступної версії; для дат до першої версії діє перша.
     * editable — версію ще можна змінити чи видалити (дата в майбутньому); current — діє сьогодні.
     */
    public record ShiftScheduleView(
            @NotNull UUID id,
            @NotNull LocalDate validFrom,
            @NotNull List<Shift> shifts,
            @NotNull boolean editable,
            @NotNull boolean current) {
    }

    /** Нова версія графіка з майбутньої дати; якщо версія з цією датою вже є — замінює її. */
    public record ShiftScheduleRequest(@NotNull LocalDate validFrom, @NotNull @Valid List<Shift> shifts) {
    }

    public record ProjectAccessView(
            @NotNull UUID userId,
            @NotNull String fullName,
            @NotNull String phone,
            @NotNull String role,
            String grantedByName,
            @NotNull OffsetDateTime grantedAt) {
    }

    public record GrantAccessRequest(@NotNull UUID userId) {
    }
}
