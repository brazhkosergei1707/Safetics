package com.safetics.review;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.safetics.core.web.ApiException;
import com.safetics.projects.ProjectDtos.ProjectView;
import com.safetics.projects.ProjectService;
import com.safetics.shifts.ShiftDtos.WorkShiftView;
import com.safetics.shifts.ShiftReportDtos.ReportCorrectionRequest;
import com.safetics.shifts.ShiftReportDtos.ReviewListItem;
import com.safetics.shifts.ShiftReportDtos.ReviewReportView;
import com.safetics.shifts.ShiftReportDtos.ShiftReportView;
import com.safetics.shifts.ShiftReportService;
import com.safetics.shifts.WorkShiftService;

/**
 * Перевірка змінних рапортів замовником (портал). Замовник бачить лише проєкти свого замовника
 * (RLS проєктів); зміни й рапорти цих проєктів читаються від імені системи. Без витрат і
 * лічильників — це внутрішнє підрядника. Дії — лише з дозволом report.review.
 */
@Service
public class ReportReviewService {

    private static final int MAX_ITEMS = 500;

    private final JdbcClient db;
    private final ProjectService projects;
    private final WorkShiftService shifts;
    private final ShiftReportService reports;

    public ReportReviewService(JdbcClient db, ProjectService projects, WorkShiftService shifts, ShiftReportService reports) {
        this.db = db;
        this.projects = projects;
        this.shifts = shifts;
        this.reports = reports;
    }

    /** Подані (і далі) рапорти видимих проєктів за період; статус — необов'язковий фільтр. */
    @Transactional(readOnly = true)
    public List<ReviewListItem> list(UUID projectId, LocalDate from, LocalDate to, String status) {
        List<UUID> visible = visibleProjects();
        if (visible.isEmpty() || (projectId != null && !visible.contains(projectId))) {
            return List.of();
        }
        List<UUID> scope = projectId != null ? List.of(projectId) : visible;
        return asSystem(() -> {
            List<Object> params = new ArrayList<>();
            StringBuilder where = new StringBuilder("r.status <> 'draft' and s.project_id in (");
            for (int i = 0; i < scope.size(); i++) {
                where.append(i == 0 ? "?" : ", ?");
                params.add(scope.get(i));
            }
            where.append(")");
            if (from != null) {
                where.append(" and s.shift_date >= ?");
                params.add(from);
            }
            if (to != null) {
                where.append(" and s.shift_date <= ?");
                params.add(to);
            }
            if (status != null && !status.isBlank()) {
                where.append(" and r.status = ?");
                params.add(status);
            }
            return db.sql("""
                            select s.id, s.project_id, p.code as project_code, a.code as asset_code, s.shift_date, s.shift_name,
                                   r.status, r.submitted_at,
                                   coalesce((select sum(i.depth_to - i.depth_from) from shift_report_interval i
                                             where i.report_id = r.id), 0) as meters,
                                   coalesce((select sum(t.end_minute - t.start_minute) from shift_report_time t
                                             join activity_code ac on ac.id = t.activity_code_id
                                             where t.report_id = r.id and ac.billable), 0) as billable
                            from shift_report r
                                     join work_shift s on s.id = r.shift_id
                                     join project p on p.id = s.project_id
                                     join asset a on a.id = s.asset_id
                            where %s
                            order by (r.status = 'submitted') desc, s.shift_date desc, s.starts_at desc
                            limit %d
                            """.formatted(where, MAX_ITEMS))
                    .params(params.toArray())
                    .query((rs, n) -> new ReviewListItem(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
                            rs.getString("project_code"), rs.getString("asset_code"), rs.getObject("shift_date", LocalDate.class),
                            rs.getString("shift_name"), rs.getString("status"), rs.getObject("submitted_at", OffsetDateTime.class),
                            rs.getBigDecimal("meters"), rs.getInt("billable")))
                    .list();
        });
    }

    @Transactional(readOnly = true)
    public ReviewReportView get(UUID shiftId) {
        List<UUID> visible = visibleProjects();
        return asSystem(() -> view(shiftId, visible));
    }

    @Transactional
    public ReviewReportView accept(UUID shiftId, int version) {
        List<UUID> visible = visibleProjects();
        return asSystem(() -> {
            view(shiftId, visible);
            reports.accept(shiftId, version);
            return view(shiftId, visible);
        });
    }

    @Transactional
    public ReviewReportView returnReport(UUID shiftId, int version, String comment) {
        List<UUID> visible = visibleProjects();
        return asSystem(() -> {
            view(shiftId, visible);
            reports.returnReport(shiftId, version, comment);
            return view(shiftId, visible);
        });
    }

    @Transactional
    public ReviewReportView correct(UUID shiftId, ReportCorrectionRequest request) {
        List<UUID> visible = visibleProjects();
        return asSystem(() -> {
            view(shiftId, visible);
            reports.correct(shiftId, request);
            return view(shiftId, visible);
        });
    }

    // ---------------------------------------------------------------- допоміжне

    /** Рапорт для замовника: лише подані й далі, без витрат і лічильників. Чужий проєкт або чернетка — 404. */
    private ReviewReportView view(UUID shiftId, List<UUID> visible) {
        WorkShiftView shift = shifts.find(shiftId).orElseThrow(ApiException::notFound);
        if (!visible.contains(shift.projectId())) {
            throw ApiException.notFound();
        }
        ShiftReportView full = reports.get(shiftId);
        if ("new".equals(full.status()) || "draft".equals(full.status())) {
            throw ApiException.notFound();
        }
        ShiftReportView report = new ShiftReportView(full.shiftId(), full.reportId(), full.status(), full.date(),
                full.startsAt(), full.durationMinutes(), full.notes(), full.times(), full.intervals(), full.quantities(),
                List.of(), List.of(), full.totals(), full.errors(), List.of(), List.of(), false, false, null,
                full.submittedAt(), full.submittedByName(), full.version(), full.acceptedAt(), full.events(),
                full.correction(), false, List.of());
        ProjectView project = projects.get(shift.projectId());
        boolean canReview = "submitted".equals(full.status()) && "active".equals(project.status()) && hasAuthority("report.review");
        return new ReviewReportView(report, project.id(), project.code(), project.name(), shift.assetCode(), shift.shiftName(),
                shift.crew(), canReview);
    }

    private List<UUID> visibleProjects() {
        return db.sql("select id from project").query(UUID.class).list();
    }

    private <T> T asSystem(Supplier<T> action) {
        String previous = db.sql("select coalesce(current_setting('app.system', true), '')").query(String.class).single();
        db.sql("select set_config('app.system', 'on', true)").query(String.class).single();
        try {
            return action.get();
        } finally {
            db.sql("select set_config('app.system', ?, true)").param(previous).query(String.class).single();
        }
    }

    private static boolean hasAuthority(String code) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getAuthorities().stream().anyMatch(a -> code.equals(a.getAuthority()));
    }
}
