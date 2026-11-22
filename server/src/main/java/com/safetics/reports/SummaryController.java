package com.safetics.reports;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.safetics.reports.SummaryDtos.SummaryView;

import io.swagger.v3.oas.annotations.tags.Tag;

/** Зведені звіти по обладнанню: ті, хто бачить зміни (task.view), у межах своїх проєктів. */
@RestController
@RequestMapping("/api/v1/reports")
@Tag(name = "reports")
@PreAuthorize("hasAuthority('task.view')")
public class SummaryController {

    private final SummaryService summaries;

    public SummaryController(SummaryService summaries) {
        this.summaries = summaries;
    }

    /** period: month / half_year / year; depthStep — крок діапазонів глибини, м. */
    @GetMapping("/summary")
    public SummaryView summary(
            @RequestParam(name = "projectId", required = false) UUID projectId,
            @RequestParam(name = "assetId", required = false) UUID assetId,
            @RequestParam(name = "from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "period", defaultValue = "month") String period,
            @RequestParam(name = "depthStep", defaultValue = "100") int depthStep,
            @RequestParam(name = "includeDrafts", defaultValue = "false") boolean includeDrafts) {
        return summaries.summary(projectId, assetId, from, to, period, depthStep, includeDrafts);
    }
}
