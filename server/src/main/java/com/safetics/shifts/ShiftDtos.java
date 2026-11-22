package com.safetics.shifts;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import com.safetics.projects.ProjectDtos.Shift;

import io.swagger.v3.oas.annotations.media.Schema;

/** Зміни основного обладнання і бригади. Поля без @NotNull можуть бути null. */
public final class ShiftDtos {

    private ShiftDtos() {
    }

    /** lead — бурильник зміни (один на зміну). minutes — відпрацьовано на зміні. */
    public record CrewMemberView(
            @NotNull UUID personnelId,
            @NotNull String fullName,
            @NotNull UUID positionId,
            @NotNull Map<String, String> positionName,
            @NotNull boolean lead,
            @NotNull int minutes,
            UUID userId) {
    }

    /** Задача зміни (змінний рапорт, чек-лист). */
    public record ShiftTaskRef(
            @NotNull UUID id,
            @NotNull long number,
            @NotNull String title,
            @NotNull String typeCode,
            @NotNull String status,
            @NotNull String statusCategory) {
    }

    /**
     * Зміна: основне обладнання + дата + зміна графіка. state за часом: upcoming — ще не почалась,
     * running — триває, finished — завершилась. source: schedule — за графіком, manual — вручну.
     */
    public record WorkShiftView(
            @NotNull UUID id,
            @NotNull UUID projectId,
            @NotNull UUID assetId,
            @NotNull String assetCode,
            String assetName,
            @NotNull LocalDate date,
            @NotNull String shiftCode,
            @NotNull String shiftName,
            @NotNull OffsetDateTime startsAt,
            @NotNull OffsetDateTime endsAt,
            @NotNull int durationMinutes,
            @NotNull @Schema(allowableValues = {"upcoming", "running", "finished"}) String state,
            @NotNull @Schema(allowableValues = {"schedule", "manual"}) String source,
            @NotNull List<CrewMemberView> crew,
            @NotNull List<ShiftTaskRef> tasks,
            @NotNull int version) {
    }

    /** Основне обладнання, призначене на проєкт у межах дат дошки. */
    public record BoardAsset(
            @NotNull UUID assetId,
            @NotNull String code,
            String name,
            @NotNull LocalDate startDate,
            LocalDate endDate,
            @NotNull @Schema(allowableValues = {"ok", "low", "medium", "high"}) String serviceLevel) {
    }

    /** Дошка «Обладнання × зміни»: рядки — обладнання, зміни графіка (на кінцеву дату) — для колонок. */
    public record ShiftBoardView(
            @NotNull List<BoardAsset> assets,
            @NotNull List<Shift> scheduleShifts,
            @NotNull List<WorkShiftView> shifts) {
    }

    /** Зміна вручну (позапланова або пропущена): час — з графіка, що діє на дату. */
    public record WorkShiftCreateRequest(
            @NotNull UUID assetId,
            @NotNull LocalDate date,
            @NotNull String shiftCode) {
    }

    /** positionId порожній — посада з призначення на проєкт; minutes порожні — тривалість зміни. */
    public record CrewEntry(
            @NotNull UUID personnelId,
            UUID positionId,
            Boolean lead,
            Integer minutes) {
    }

    public record CrewUpdateRequest(
            @NotNull @Valid List<CrewEntry> crew,
            @NotNull Integer version) {
    }
}
