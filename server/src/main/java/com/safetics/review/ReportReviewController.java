package com.safetics.review;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.safetics.shifts.ShiftReportDtos.ReportActionRequest;
import com.safetics.shifts.ShiftReportDtos.ReportCorrectionRequest;
import com.safetics.shifts.ShiftReportDtos.ReportReturnRequest;
import com.safetics.shifts.ShiftReportDtos.ReviewListItem;
import com.safetics.shifts.ShiftReportDtos.ReviewReportView;

import io.swagger.v3.oas.annotations.tags.Tag;

/** Портал замовника: рапорти на перевірку. Перегляд — ролі порталу, дії — report.review. */
@RestController
@RequestMapping("/api/v1/review/reports")
@Tag(name = "review")
@PreAuthorize("hasAuthority('report.review') or hasAuthority('monthly_report.approve')")
public class ReportReviewController {

    private final ReportReviewService review;

    public ReportReviewController(ReportReviewService review) {
        this.review = review;
    }

    @GetMapping
    public List<ReviewListItem> list(
            @RequestParam(name = "projectId", required = false) UUID projectId,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "status", required = false) String status) {
        return review.list(projectId, from, to, status);
    }

    @GetMapping("/{shiftId}")
    public ReviewReportView get(@PathVariable("shiftId") UUID shiftId) {
        return review.get(shiftId);
    }

    @PostMapping("/{shiftId}/accept")
    @PreAuthorize("hasAuthority('report.review')")
    public ReviewReportView accept(@PathVariable("shiftId") UUID shiftId, @Valid @RequestBody ReportActionRequest request) {
        return review.accept(shiftId, request.version());
    }

    @PostMapping("/{shiftId}/return")
    @PreAuthorize("hasAuthority('report.review')")
    public ReviewReportView returnReport(@PathVariable("shiftId") UUID shiftId, @Valid @RequestBody ReportReturnRequest request) {
        return review.returnReport(shiftId, request.version(), request.comment());
    }

    @PostMapping("/{shiftId}/correct")
    @PreAuthorize("hasAuthority('report.review')")
    public ReviewReportView correct(@PathVariable("shiftId") UUID shiftId, @Valid @RequestBody ReportCorrectionRequest request) {
        return review.correct(shiftId, request);
    }
}
