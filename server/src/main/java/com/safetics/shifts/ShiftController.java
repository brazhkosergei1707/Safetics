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
import com.safetics.shifts.ShiftReportDtos.GenerateShiftsRequest;
import com.safetics.shifts.ShiftReportDtos.GenerateShiftsResponse;
import com.safetics.shifts.ShiftReportDtos.ReportActionRequest;
import com.safetics.shifts.ShiftReportDtos.ShiftReportRequest;
import com.safetics.shifts.ShiftReportDtos.ShiftReportView;

import io.swagger.v3.oas.annotations.tags.Tag;

/** Зміни й бригади: перегляд — task.view (портал не бачить), зміни — менеджер і супервайзер проєкту. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "shifts")
@PreAuthorize("hasAuthority('task.view')")
public class ShiftController {

    private final WorkShiftService shifts;
    private final ShiftReportService reports;
    private final ShiftChecklistService checklists;

    public ShiftController(WorkShiftService shifts, ShiftReportService reports, ShiftChecklistService checklists) {
        this.shifts = shifts;
        this.reports = reports;
        this.checklists = checklists;
    }

    @GetMapping("/shifts/{id}/checklist")
    public ShiftChecklistService.ShiftChecklistView checklist(@PathVariable("id") UUID id) {
        return checklists.get(id);
    }

    @PostMapping("/shifts/{id}/checklist")
    public ShiftChecklistService.ShiftChecklistView submitChecklist(
            @PathVariable("id") UUID id, @Valid @RequestBody ShiftChecklistService.ChecklistSubmitRequest request) {
        return checklists.submit(id, request);
    }

    @PostMapping("/projects/{projectId}/shifts/generate")
    public GenerateShiftsResponse generate(@PathVariable("projectId") UUID projectId,
                                           @Valid @RequestBody GenerateShiftsRequest request) {
        return new GenerateShiftsResponse(shifts.generate(projectId, request.from(), request.to(), request.assetId()));
    }

    @GetMapping("/shifts/{id}/report")
    public ShiftReportView report(@PathVariable("id") UUID id) {
        return reports.get(id);
    }

    @PutMapping("/shifts/{id}/report")
    public ShiftReportView saveReport(@PathVariable("id") UUID id, @Valid @RequestBody ShiftReportRequest request) {
        return reports.save(id, request);
    }

    @PostMapping("/shifts/{id}/report/submit")
    public ShiftReportView submitReport(@PathVariable("id") UUID id, @Valid @RequestBody ReportActionRequest request) {
        return reports.submit(id, request.version());
    }

    @PostMapping("/shifts/{id}/report/reopen")
    public ShiftReportView reopenReport(@PathVariable("id") UUID id, @Valid @RequestBody ReportActionRequest request) {
        return reports.reopen(id, request.version());
    }

    /** Погодитись з правкою замовника: значення замовника — у рапорт, «Перевірено». */
    @PostMapping("/shifts/{id}/report/agree")
    public ShiftReportView agreeReport(@PathVariable("id") UUID id, @Valid @RequestBody ReportActionRequest request) {
        return reports.agree(id, request.version());
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
