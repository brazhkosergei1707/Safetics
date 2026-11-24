package com.safetics.monthly;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.safetics.core.web.ApiException;
import com.safetics.monthly.MonthlyDtos.AdjustmentRequest;
import com.safetics.monthly.MonthlyDtos.InclusionRequest;
import com.safetics.monthly.MonthlyDtos.MonthView;
import com.safetics.monthly.MonthlyDtos.MonthlyReportView;
import com.safetics.monthly.MonthlyDtos.MonthlyReturnRequest;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Місячний звіт: підрядник (перегляд — task.view, дії — project.manage у сервісі) і портал
 * замовника (перегляд — ролі порталу, затвердження / повернення — monthly_report.approve).
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "monthly-reports")
public class MonthlyReportController {

    private final MonthlyReportService monthly;

    public MonthlyReportController(MonthlyReportService monthly) {
        this.monthly = monthly;
    }

    @GetMapping("/projects/{projectId}/months/{month}")
    @PreAuthorize("hasAuthority('task.view')")
    public MonthView month(@PathVariable("projectId") UUID projectId, @PathVariable("month") String month) {
        return monthly.month(projectId, parse(month));
    }

    @PostMapping("/projects/{projectId}/months/{month}/generate")
    @PreAuthorize("hasAuthority('project.manage')")
    public MonthlyReportView generate(@PathVariable("projectId") UUID projectId, @PathVariable("month") String month) {
        return monthly.generate(projectId, parse(month));
    }

    @PostMapping("/projects/{projectId}/months/{month}/inclusions")
    @PreAuthorize("hasAuthority('project.manage')")
    public MonthView include(@PathVariable("projectId") UUID projectId, @PathVariable("month") String month,
                             @Valid @RequestBody InclusionRequest request) {
        return monthly.include(projectId, parse(month), request.shiftId(), request.included());
    }

    @PostMapping("/projects/{projectId}/months/{month}/adjustments")
    @PreAuthorize("hasAuthority('project.manage')")
    public MonthView addAdjustment(@PathVariable("projectId") UUID projectId, @PathVariable("month") String month,
                                   @Valid @RequestBody AdjustmentRequest request) {
        return monthly.addAdjustment(projectId, parse(month), request);
    }

    @DeleteMapping("/projects/{projectId}/months/{month}/adjustments/{adjustmentId}")
    @PreAuthorize("hasAuthority('project.manage')")
    public MonthView deleteAdjustment(@PathVariable("projectId") UUID projectId, @PathVariable("month") String month,
                                      @PathVariable("adjustmentId") UUID adjustmentId) {
        return monthly.deleteAdjustment(projectId, parse(month), adjustmentId);
    }

    @GetMapping("/monthly-reports/{id}")
    @PreAuthorize("hasAuthority('task.view')")
    public MonthlyReportView get(@PathVariable("id") UUID id) {
        return monthly.get(id);
    }

    @PostMapping("/monthly-reports/{id}/send")
    @PreAuthorize("hasAuthority('project.manage')")
    public MonthlyReportView send(@PathVariable("id") UUID id) {
        return monthly.send(id);
    }

    // ---------------------------------------------------------------- портал

    @GetMapping("/review/monthly-reports")
    @PreAuthorize("hasAuthority('report.review') or hasAuthority('monthly_report.approve')")
    public List<MonthlyReportView> portalList() {
        return monthly.portalList();
    }

    @GetMapping("/review/monthly-reports/{id}")
    @PreAuthorize("hasAuthority('report.review') or hasAuthority('monthly_report.approve')")
    public MonthlyReportView portalGet(@PathVariable("id") UUID id) {
        return monthly.portalGet(id);
    }

    @PostMapping("/review/monthly-reports/{id}/approve")
    @PreAuthorize("hasAuthority('monthly_report.approve')")
    public MonthlyReportView approve(@PathVariable("id") UUID id) {
        return monthly.approve(id);
    }

    @PostMapping("/review/monthly-reports/{id}/return")
    @PreAuthorize("hasAuthority('monthly_report.approve')")
    public MonthlyReportView returnReport(@PathVariable("id") UUID id, @Valid @RequestBody MonthlyReturnRequest request) {
        return monthly.returnReport(id, request.comment());
    }

    private static YearMonth parse(String month) {
        try {
            return YearMonth.parse(month);
        } catch (DateTimeParseException e) {
            throw ApiException.validation("month", "invalid_format");
        }
    }
}
