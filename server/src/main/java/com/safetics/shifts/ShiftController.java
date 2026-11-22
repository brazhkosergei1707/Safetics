package com.safetics.shifts;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.safetics.shifts.ShiftDtos.CrewUpdateRequest;
import com.safetics.shifts.ShiftDtos.ShiftBoardView;
import com.safetics.shifts.ShiftDtos.WorkShiftCreateRequest;
import com.safetics.shifts.ShiftDtos.WorkShiftView;

import io.swagger.v3.oas.annotations.tags.Tag;

/** Зміни й бригади: перегляд — task.view (портал не бачить), зміни — менеджер і супервайзер проєкту. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "shifts")
@PreAuthorize("hasAuthority('task.view')")
public class ShiftController {

    private final WorkShiftService shifts;

    public ShiftController(WorkShiftService shifts) {
        this.shifts = shifts;
    }

    @GetMapping("/projects/{projectId}/shift-board")
    public ShiftBoardView board(
            @PathVariable("projectId") UUID projectId,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return shifts.board(projectId, from, to);
    }

    @PostMapping("/projects/{projectId}/shifts")
    @ResponseStatus(HttpStatus.CREATED)
    public WorkShiftView create(@PathVariable("projectId") UUID projectId, @Valid @RequestBody WorkShiftCreateRequest request) {
        return shifts.createManual(projectId, request);
    }

    @GetMapping("/shifts/{id}")
    public WorkShiftView get(@PathVariable("id") UUID id) {
        return shifts.get(id);
    }

    @PutMapping("/shifts/{id}/crew")
    public WorkShiftView updateCrew(@PathVariable("id") UUID id, @Valid @RequestBody CrewUpdateRequest request) {
        return shifts.updateCrew(id, request);
    }
}
