package com.safetics.monthly;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.safetics.alerts.AlertService;
import com.safetics.core.audit.AuditService;
import com.safetics.core.tenancy.TenantContext;
import com.safetics.core.web.ApiException;
import com.safetics.monthly.MonthlyDtos.AdjustmentRequest;
import com.safetics.monthly.MonthlyDtos.AdjustmentView;
import com.safetics.monthly.MonthlyDtos.AssetBlock;
import com.safetics.monthly.MonthlyDtos.HoursLine;
import com.safetics.monthly.MonthlyDtos.MetersLine;
import com.safetics.monthly.MonthlyDtos.MonthView;
import com.safetics.monthly.MonthlyDtos.MonthlyContent;
import com.safetics.monthly.MonthlyDtos.MonthlyReportView;
import com.safetics.monthly.MonthlyDtos.MonthlyTotals;
import com.safetics.monthly.MonthlyDtos.QuantityLine;
import com.safetics.monthly.MonthlyDtos.ShiftOption;
import com.safetics.monthly.MonthlyDtos.UnreviewedReport;
import com.safetics.projects.ProjectDtos.ProjectView;
import com.safetics.projects.ProjectService;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Місячний звіт проєкту. Основна частина — перевірені замовником рапорти й неперевірені, які
 * менеджер включив; решта неперевірених — окремим блоком; ручні коригування з коментарем.
 * Кожна генерація — нова версія зі знімком; надісланий звіт не змінюється. Затвердження
 * менеджером замовника закриває місяць.
 */
@Service
public class MonthlyReportService {

    private static final TypeReference<Map<String, String>> NAME = new TypeReference<>() {
    };
    private static final Set<String> UNREVIEWED = Set.of("submitted", "corrected", "returned");
    private static final Set<String> KINDS = Set.of("meters", "hours", "quantity");

    private final JdbcClient db;
    private final JsonMapper json;
    private final ProjectService projects;
    private final MonthCloses closes;
    private final AlertService alerts;
    private final AuditService audit;

    public MonthlyReportService(JdbcClient db, JsonMapper json, ProjectService projects, MonthCloses closes,
                                AlertService alerts, AuditService audit) {
        this.db = db;
        this.json = json;
        this.projects = projects;
        this.closes = closes;
        this.alerts = alerts;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- підрядник

    @Transactional(readOnly = true)
    public MonthView month(UUID projectId, YearMonth month) {
        ProjectView project = projects.get(projectId);
        LocalDate first = month.atDay(1);
        LocalDate previous = first.minusMonths(1);
        List<ShiftOption> correctable = !closes.isClosed(projectId, previous) ? List.of() : asSystem(() -> db.sql("""
                        select s.id, a.code, s.shift_date, s.shift_name
                        from work_shift s join asset a on a.id = s.asset_id join shift_report r on r.shift_id = s.id
                        where s.project_id = ? and s.shift_date >= ? and s.shift_date < ?
                        order by s.shift_date, s.starts_at, a.code
                        """)
                .params(projectId, previous, first)
                .query((rs, n) -> new ShiftOption(rs.getObject("id", UUID.class),
                        rs.getString("code") + " · " + rs.getObject("shift_date", LocalDate.class) + " · " + rs.getString("shift_name")))
                .list());
        return new MonthView(project.id(), first, closes.isClosed(projectId, first), asSystem(() -> compute(projectId, month)),
                versions(projectId, first), hasAuthority("project.manage"), correctable);
    }

    /** Нова версія зі знімком поточних даних; попередня чернетка — «замінено». */
    @Transactional
    public MonthlyReportView generate(UUID projectId, YearMonth month) {
        ProjectView project = requireManage(projectId);
        LocalDate first = month.atDay(1);
        requireOpenMonth(projectId, month);
        if (month.isAfter(YearMonth.now(ZoneOffset.UTC))) {
            throw ApiException.validation("month", "future");
        }
        Optional<Latest> latest = latest(projectId, first);
        if (latest.map(l -> "sent".equals(l.status())).orElse(false)) {
            throw ApiException.conflict("report_sent");
        }
        db.sql("update monthly_report set status = 'superseded' where project_id = ? and month = ? and status = 'draft'")
                .params(projectId, first).update();
        MonthlyContent content = asSystem(() -> compute(projectId, month));
        int version = latest.map(Latest::version).orElse(0) + 1;
        UUID id = db.sql("""
                        insert into monthly_report (company_id, project_id, month, version, content, generated_by)
                        values (?, ?, ?, ?, cast(? as jsonb), ?)
                        returning id
                        """)
                .params(TenantContext.requireCompanyId(), project.id(), first, version, json.writeValueAsString(content),
                        currentUserId())
                .query(UUID.class).single();
        audit.record("monthly_report", id, "generate", Map.of("month", month.toString(), "version", version));
        return get(id);
    }

    /** Надіслати замовнику: лише остання версія-чернетка; алерт менеджерам замовника. */
    @Transactional
    public MonthlyReportView send(UUID reportId) {
        MonthlyReportView report = get(reportId);
        requireManage(report.projectId());
        requireOpenMonth(report.projectId(), YearMonth.from(report.month()));
        if (!"draft".equals(report.status())) {
            throw ApiException.conflict("report_not_draft");
        }
        db.sql("update monthly_report set status = 'sent', sent_at = now(), sent_by = ? where id = ?")
                .params(currentUserId(), reportId).update();
        ProjectView project = projects.get(report.projectId());
        List<UUID> clientManagers = project.customerId() == null ? List.of() : asSystem(() -> db.sql("""
                        select distinct c.user_id
                        from customer_contact c
                                 join membership m on m.company_id = c.company_id and m.user_id = c.user_id and m.status = 'active'
                                 join role r on r.id = m.role_id and r.code = 'client_manager'
                        where c.customer_id = ? and c.user_id is not null
                        """).param(project.customerId()).query(UUID.class).list());
        alerts.notifyUsers(project.id(), "monthly_sent", label(report) + " — v" + report.version(), null, clientManagers);
        audit.record("monthly_report", reportId, "send", Map.of("version", report.version()));
        return get(reportId);
    }

    /** Включити / виключити неперевірений рапорт з основної частини (для наступної генерації). */
    @Transactional
    public MonthView include(UUID projectId, YearMonth month, UUID shiftId, boolean included) {
        requireManage(projectId);
        requireOpenMonth(projectId, month);
        LocalDate first = month.atDay(1);
        boolean ofMonth = asSystem(() -> Boolean.TRUE.equals(db.sql("""
                        select exists (select 1 from work_shift where id = ? and project_id = ? and shift_date >= ? and shift_date < ?)
                        """).params(shiftId, projectId, first, first.plusMonths(1)).query(Boolean.class).single()));
        if (!ofMonth) {
            throw ApiException.validation("shiftId", "not_found");
        }
        if (included) {
            db.sql("""
                            insert into monthly_report_inclusion (company_id, project_id, month, shift_id, created_by)
                            values (?, ?, ?, ?, ?)
                            on conflict do nothing
                            """)
                    .params(TenantContext.requireCompanyId(), projectId, first, shiftId, currentUserId()).update();
        } else {
            db.sql("delete from monthly_report_inclusion where project_id = ? and month = ? and shift_id = ?")
                    .params(projectId, first, shiftId).update();
        }
        audit.record("monthly_report", projectId, included ? "include" : "exclude", Map.of("month", month.toString(),
                "shiftId", shiftId));
        return month(projectId, month);
    }

    /**
     * Ручне коригування місяця з коментарем. correctsShiftId — коригувальний запис: зміна цього
     * проєкту з уже закритого місяця, рапорт якої виправляється.
     */
    @Transactional
    public MonthView addAdjustment(UUID projectId, YearMonth month, AdjustmentRequest request) {
        requireManage(projectId);
        requireOpenMonth(projectId, month);
        Map<String, String> errors = new LinkedHashMap<>();
        if (!KINDS.contains(request.kind())) {
            errors.put("kind", "invalid_option");
        }
        if (request.amount().signum() == 0) {
            errors.put("amount", "required");
        }
        if ("quantity".equals(request.kind()) && (request.unit() == null || request.unit().isBlank())) {
            errors.put("unit", "required");
        }
        if (request.assetId() != null && !exists("select 1 from asset where id = ?", request.assetId())) {
            errors.put("assetId", "not_found");
        }
        if (request.correctsShiftId() != null) {
            Optional<LocalDate> date = asSystem(() -> db.sql("select shift_date from work_shift where id = ? and project_id = ?")
                    .params(request.correctsShiftId(), projectId).query(LocalDate.class).optional());
            if (date.isEmpty()) {
                errors.put("correctsShiftId", "not_found");
            } else if (!closes.isClosed(projectId, date.get())) {
                errors.put("correctsShiftId", "month_not_closed");
            }
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        UUID id = db.sql("""
                        insert into monthly_adjustment (company_id, project_id, month, kind, asset_id, amount, unit, comment,
                                                        corrects_shift_id, created_by)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        returning id
                        """)
                .params(TenantContext.requireCompanyId(), projectId, month.atDay(1), request.kind(), request.assetId(),
                        request.amount(), blankToNull(request.unit()), request.comment().trim(), request.correctsShiftId(),
                        currentUserId())
                .query(UUID.class).single();
        audit.record("monthly_adjustment", id, "create", Map.of("month", month.toString(), "kind", request.kind(),
                "amount", request.amount()));
        return month(projectId, month);
    }

    @Transactional
    public MonthView deleteAdjustment(UUID projectId, YearMonth month, UUID adjustmentId) {
        requireManage(projectId);
        requireOpenMonth(projectId, month);
        int deleted = db.sql("delete from monthly_adjustment where id = ? and project_id = ? and month = ?")
                .params(adjustmentId, projectId, month.atDay(1)).update();
        if (deleted == 0) {
            throw ApiException.notFound();
        }
        audit.record("monthly_adjustment", adjustmentId, "delete", Map.of("month", month.toString()));
        return month(projectId, month);
    }

    @Transactional(readOnly = true)
    public MonthlyReportView get(UUID reportId) {
        return db.sql(SELECT + " where m.id = ?").param(reportId).query((rs, n) -> map(rs)).optional()
                .orElseThrow(ApiException::notFound);
    }

    // ---------------------------------------------------------------- портал

    /** Надіслані замовнику звіти (і рішення по них) видимих проєктів. */
    @Transactional(readOnly = true)
    public List<MonthlyReportView> portalList() {
        return db.sql(SELECT + " where m.status in ('sent', 'approved', 'returned') order by m.month desc, p.code, m.version desc")
                .query((rs, n) -> map(rs)).list();
    }

    @Transactional(readOnly = true)
    public MonthlyReportView portalGet(UUID reportId) {
        MonthlyReportView report = get(reportId);
        if (!Set.of("sent", "approved", "returned").contains(report.status())) {
            throw ApiException.notFound();
        }
        return report;
    }

    /** Менеджер замовника затверджує: місяць закрито, рапорти заблоковані. */
    @Transactional
    public MonthlyReportView approve(UUID reportId) {
        MonthlyReportView report = requireSent(reportId);
        db.sql("update monthly_report set status = 'approved', decided_at = now(), decided_by = ? where id = ?")
                .params(currentUserId(), reportId).update();
        db.sql("""
                        insert into project_month_close (company_id, project_id, month, report_id, closed_by)
                        values (?, ?, ?, ?, ?)
                        """)
                .params(TenantContext.requireCompanyId(), report.projectId(), report.month(), reportId, currentUserId()).update();
        notifyContractor(reportId, "monthly_approved", label(report));
        audit.record("monthly_report", reportId, "approve", Map.of("version", report.version()));
        return get(reportId);
    }

    /** Менеджер замовника повертає з коментарем: підрядник переробляє рапорти й генерує нову версію. */
    @Transactional
    public MonthlyReportView returnReport(UUID reportId, String comment) {
        MonthlyReportView report = requireSent(reportId);
        db.sql("update monthly_report set status = 'returned', decided_at = now(), decided_by = ?, decision_comment = ? where id = ?")
                .params(currentUserId(), comment.trim(), reportId).update();
        notifyContractor(reportId, "monthly_returned", label(report) + ": " + comment.trim());
        audit.record("monthly_report", reportId, "return", Map.of("version", report.version()));
        return get(reportId);
    }

    // ---------------------------------------------------------------- розрахунок

    private record ReportRow(UUID reportId, UUID shiftId, LocalDate date, UUID assetId, String assetCode, String shiftName,
                             String status, BigDecimal meters, int billable) {
    }

    /** Розрахунок місяця за поточними даними (від імені системи; доступ до проєкту вже перевірено). */
    private MonthlyContent compute(UUID projectId, YearMonth month) {
        LocalDate first = month.atDay(1);
        LocalDate next = first.plusMonths(1);
        List<ReportRow> rows = db.sql("""
                        select r.id as report_id, s.id as shift_id, s.shift_date, s.asset_id, a.code as asset_code, s.shift_name,
                               r.status,
                               coalesce((select sum(i.depth_to - i.depth_from) from shift_report_interval i
                                         where i.report_id = r.id), 0) as meters,
                               coalesce((select sum(t.end_minute - t.start_minute) from shift_report_time t
                                         join activity_code ac on ac.id = t.activity_code_id
                                         where t.report_id = r.id and ac.billable), 0) as billable
                        from shift_report r
                                 join work_shift s on s.id = r.shift_id
                                 join asset a on a.id = s.asset_id
                        where s.project_id = ? and s.shift_date >= ? and s.shift_date < ?
                        order by s.shift_date, s.starts_at, a.code
                        """)
                .params(projectId, first, next)
                .query((rs, n) -> new ReportRow(rs.getObject("report_id", UUID.class), rs.getObject("shift_id", UUID.class),
                        rs.getObject("shift_date", LocalDate.class), rs.getObject("asset_id", UUID.class),
                        rs.getString("asset_code"), rs.getString("shift_name"), rs.getString("status"),
                        rs.getBigDecimal("meters"), rs.getInt("billable")))
                .list();
        Set<UUID> included = new HashSet<>(db.sql("select shift_id from monthly_report_inclusion where project_id = ? and month = ?")
                .params(projectId, first).query(UUID.class).list());

        List<ReportRow> main = new ArrayList<>();
        List<UnreviewedReport> unreviewed = new ArrayList<>();
        int accepted = 0;
        int drafts = 0;
        for (ReportRow row : rows) {
            if ("accepted".equals(row.status())) {
                main.add(row);
                accepted++;
            } else if (UNREVIEWED.contains(row.status())) {
                boolean inc = included.contains(row.shiftId());
                if (inc) {
                    main.add(row);
                }
                unreviewed.add(new UnreviewedReport(row.shiftId(), row.date(), row.assetCode(), row.shiftName(), row.status(),
                        row.meters(), row.billable(), inc));
            } else {
                drafts++;
            }
        }

        Map<UUID, List<ReportRow>> byAsset = new LinkedHashMap<>();
        main.stream().sorted((a, b) -> a.assetCode().compareTo(b.assetCode()))
                .forEach(r -> byAsset.computeIfAbsent(r.assetId(), k -> new ArrayList<>()).add(r));
        List<AssetBlock> assets = new ArrayList<>();
        BigDecimal meters = BigDecimal.ZERO;
        int billable = 0;
        Map<String, BigDecimal> quantityByUnit = new LinkedHashMap<>();
        for (Map.Entry<UUID, List<ReportRow>> entry : byAsset.entrySet()) {
            List<UUID> ids = entry.getValue().stream().map(ReportRow::reportId).toList();
            String in = placeholders(ids.size());
            BigDecimal assetMeters = entry.getValue().stream().map(ReportRow::meters).reduce(BigDecimal.ZERO, BigDecimal::add);
            int assetBillable = entry.getValue().stream().mapToInt(ReportRow::billable).sum();
            List<MetersLine> byHole = db.sql("""
                            select sv.code, i.core_size, sum(i.depth_to - i.depth_from) as meters, sum(i.core_length) as core
                            from shift_report_interval i join service sv on sv.id = i.service_id
                            where i.report_id in (%s)
                            group by sv.code, i.core_size
                            order by sv.code, i.core_size
                            """.formatted(in))
                    .params(ids.toArray())
                    .query((rs, n) -> new MetersLine(rs.getString("code"), rs.getString("core_size"), rs.getBigDecimal("meters"),
                            rs.getBigDecimal("core")))
                    .list();
            List<HoursLine> hours = db.sql("""
                            select ac.code, ac.name, sum(t.end_minute - t.start_minute) as minutes
                            from shift_report_time t join activity_code ac on ac.id = t.activity_code_id
                            where t.report_id in (%s) and ac.billable
                            group by ac.code, ac.name
                            order by ac.code
                            """.formatted(in))
                    .params(ids.toArray())
                    .query((rs, n) -> new HoursLine(rs.getString("code"), json.readValue(rs.getString("name"), NAME),
                            rs.getInt("minutes")))
                    .list();
            List<QuantityLine> quantities = db.sql("""
                            select sv.code, st.unit, sum(q.quantity) as quantity
                            from shift_report_quantity q
                                     join service sv on sv.id = q.service_id
                                     join service_type st on st.id = sv.service_type_id
                            where q.report_id in (%s)
                            group by sv.code, st.unit
                            order by sv.code
                            """.formatted(in))
                    .params(ids.toArray())
                    .query((rs, n) -> new QuantityLine(rs.getString("code"), rs.getString("unit"), rs.getBigDecimal("quantity")))
                    .list();
            quantities.forEach(q -> quantityByUnit.merge(q.unit() == null ? "" : q.unit(), q.quantity(), BigDecimal::add));
            assets.add(new AssetBlock(entry.getKey(), entry.getValue().get(0).assetCode(), entry.getValue().size(), assetMeters,
                    byHole, assetBillable, hours, quantities));
            meters = meters.add(assetMeters);
            billable += assetBillable;
        }

        List<AdjustmentView> adjustments = adjustments(projectId, first);
        BigDecimal adjMeters = BigDecimal.ZERO;
        BigDecimal adjHours = BigDecimal.ZERO;
        for (AdjustmentView adjustment : adjustments) {
            switch (adjustment.kind()) {
                case "meters" -> adjMeters = adjMeters.add(adjustment.amount());
                case "hours" -> adjHours = adjHours.add(adjustment.amount());
                default -> quantityByUnit.merge(adjustment.unit() == null ? "" : adjustment.unit(), adjustment.amount(),
                        BigDecimal::add);
            }
        }
        List<QuantityLine> totalQuantities = quantityByUnit.entrySet().stream()
                .map(e -> new QuantityLine(null, e.getKey().isEmpty() ? null : e.getKey(), e.getValue())).toList();
        int totalMinutes = billable + adjHours.multiply(BigDecimal.valueOf(60)).setScale(0, java.math.RoundingMode.HALF_UP).intValue();
        MonthlyTotals totals = new MonthlyTotals(meters.add(adjMeters), totalMinutes, totalQuantities, adjMeters, adjHours);
        return new MonthlyContent(assets, unreviewed, adjustments, totals, accepted, drafts);
    }

    private List<AdjustmentView> adjustments(UUID projectId, LocalDate month) {
        return db.sql("""
                        select ad.id, ad.kind, ad.asset_id, a.code as asset_code, ad.amount, ad.unit, ad.comment, ad.corrects_shift_id,
                               ws.shift_date as corrects_date, wa.code as corrects_asset, ws.shift_name as corrects_shift,
                               u.full_name, ad.created_at
                        from monthly_adjustment ad
                                 left join asset a on a.id = ad.asset_id
                                 left join work_shift ws on ws.id = ad.corrects_shift_id
                                 left join asset wa on wa.id = ws.asset_id
                                 left join app_user u on u.id = ad.created_by
                        where ad.project_id = ? and ad.month = ?
                        order by ad.created_at
                        """)
                .params(projectId, month)
                .query((rs, n) -> {
                    LocalDate date = rs.getObject("corrects_date", LocalDate.class);
                    String label = date == null ? null
                            : rs.getString("corrects_asset") + " · " + date + " · " + rs.getString("corrects_shift");
                    return new AdjustmentView(rs.getObject("id", UUID.class), rs.getString("kind"),
                            rs.getObject("asset_id", UUID.class), rs.getString("asset_code"), rs.getBigDecimal("amount"),
                            rs.getString("unit"), rs.getString("comment"), rs.getObject("corrects_shift_id", UUID.class), label,
                            rs.getString("full_name"), rs.getObject("created_at", OffsetDateTime.class));
                })
                .list();
    }

    // ---------------------------------------------------------------- допоміжне

    private static final String SELECT = """
            select m.id, m.project_id, p.code as project_code, p.name as project_name, c.name as customer_name, m.month,
                   m.version, m.status, m.content, m.generated_at, gu.full_name as generated_by_name, m.sent_at, m.decided_at,
                   du.full_name as decided_by_name, m.decision_comment
            from monthly_report m
                     join project p on p.id = m.project_id
                     left join customer c on c.id = p.customer_id
                     left join app_user gu on gu.id = m.generated_by
                     left join app_user du on du.id = m.decided_by
            """;

    private MonthlyReportView map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new MonthlyReportView(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getString("project_code"), rs.getString("project_name"), rs.getString("customer_name"),
                rs.getObject("month", LocalDate.class), rs.getInt("version"), rs.getString("status"),
                json.readValue(rs.getString("content"), MonthlyContent.class), rs.getObject("generated_at", OffsetDateTime.class),
                rs.getString("generated_by_name"), rs.getObject("sent_at", OffsetDateTime.class),
                rs.getObject("decided_at", OffsetDateTime.class), rs.getString("decided_by_name"),
                rs.getString("decision_comment"));
    }

    private List<MonthlyReportView> versions(UUID projectId, LocalDate month) {
        return db.sql(SELECT + " where m.project_id = ? and m.month = ? order by m.version desc").params(projectId, month)
                .query((rs, n) -> map(rs)).list();
    }

    private record Latest(UUID id, int version, String status) {
    }

    private Optional<Latest> latest(UUID projectId, LocalDate month) {
        return db.sql("""
                        select id, version, status from monthly_report where project_id = ? and month = ?
                        order by version desc limit 1
                        """)
                .params(projectId, month)
                .query((rs, n) -> new Latest(rs.getObject("id", UUID.class), rs.getInt("version"), rs.getString("status")))
                .optional();
    }

    private MonthlyReportView requireSent(UUID reportId) {
        MonthlyReportView report = portalGet(reportId);
        if (!"sent".equals(report.status())) {
            throw ApiException.conflict("report_not_sent");
        }
        return report;
    }

    private ProjectView requireManage(UUID projectId) {
        ProjectView project = projects.get(projectId);
        if (!hasAuthority("project.manage")) {
            throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "forbidden", Map.of());
        }
        return project;
    }

    private void requireOpenMonth(UUID projectId, YearMonth month) {
        closes.requireOpen(projectId, month.atDay(1));
    }

    /** Відповідальний менеджер проєкту, хто генерував і хто надіслав. */
    private void notifyContractor(UUID reportId, String kind, String title) {
        List<UUID> users = asSystem(() -> db.sql("""
                        select p.manager_id, m.generated_by, m.sent_by
                        from monthly_report m join project p on p.id = m.project_id
                        where m.id = ?
                        """)
                .param(reportId)
                .query((rs, n) -> {
                    List<UUID> list = new ArrayList<>();
                    for (String column : List.of("manager_id", "generated_by", "sent_by")) {
                        UUID id = rs.getObject(column, UUID.class);
                        if (id != null) {
                            list.add(id);
                        }
                    }
                    return list;
                })
                .single());
        UUID projectId = get(reportId).projectId();
        alerts.notifyUsers(projectId, kind, title, null, users);
    }

    private static String label(MonthlyReportView report) {
        return report.projectCode() + " · " + YearMonth.from(report.month());
    }

    private boolean exists(String sql, Object... params) {
        return Boolean.TRUE.equals(db.sql("select exists (" + sql + ")").params(params).query(Boolean.class).single());
    }

    private static String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
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
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getAuthorities().stream().anyMatch(a -> code.equals(a.getAuthority()));
    }

    private static UUID currentUserId() {
        return TenantContext.current().map(TenantContext.Tenant::userId).orElse(null);
    }
}
