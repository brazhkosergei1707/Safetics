package com.safetics.shifts;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.safetics.core.audit.AuditService;
import com.safetics.core.tenancy.TenantContext;
import com.safetics.core.web.ApiException;
import com.safetics.projects.ProjectDtos.ProjectView;
import com.safetics.projects.ProjectRoles;
import com.safetics.projects.ProjectService;
import com.safetics.shifts.ShiftDtos.WorkShiftView;
import com.safetics.shifts.ShiftReportDtos.CodeTotal;
import com.safetics.shifts.ShiftReportDtos.ConsumableRequest;
import com.safetics.shifts.ShiftReportDtos.ConsumableView;
import com.safetics.shifts.ShiftReportDtos.IntervalRequest;
import com.safetics.shifts.ShiftReportDtos.IntervalStart;
import com.safetics.shifts.ShiftReportDtos.IntervalView;
import com.safetics.shifts.ShiftReportDtos.MeterRequest;
import com.safetics.shifts.ShiftReportDtos.MeterView;
import com.safetics.shifts.ShiftReportDtos.QuantityRequest;
import com.safetics.shifts.ShiftReportDtos.QuantityView;
import com.safetics.shifts.ShiftReportDtos.ReportIssue;
import com.safetics.shifts.ShiftReportDtos.ReportTotals;
import com.safetics.shifts.ShiftReportDtos.ShiftReportRequest;
import com.safetics.shifts.ShiftReportDtos.ShiftReportView;
import com.safetics.shifts.ShiftReportDtos.TimeEntryRequest;
import com.safetics.shifts.ShiftReportDtos.TimeEntryView;
import com.safetics.tasks.TaskDtos.TaskView;
import com.safetics.tasks.TaskWorkflow;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Змінний рапорт: чернетка → подано (перевірка замовником — крок 6).
 *
 * <p>Заповнюють бурильник зміни (виконавець задачі «Змінний рапорт»), супервайзер проєкту й
 * менеджер. Збереження переводить задачу рапорту в «Чернетка» (якщо її не блокує чек-лист),
 * подання — у «Подано»; подати можна лише без помилок журналу часу і без незавершених блокуючих
 * задач (чек-лист). Після подання рапорт і бригаду змінити не можна; повернути в чернетку —
 * менеджер або супервайзер.
 */
@Service
public class ShiftReportService {

    static final String REPORT_TASK_TYPE = "shift_report";
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final TypeReference<Map<String, String>> NAME = new TypeReference<>() {
    };

    private record ReportRow(UUID id, String status, String notes, int version, OffsetDateTime submittedAt,
                             String submittedByName) {
    }

    private final JdbcClient db;
    private final JsonMapper json;
    private final ProjectService projects;
    private final ProjectRoles roles;
    private final WorkShiftService shifts;
    private final TaskWorkflow tasks;
    private final AuditService audit;

    public ShiftReportService(JdbcClient db, JsonMapper json, ProjectService projects, ProjectRoles roles,
                              WorkShiftService shifts, TaskWorkflow tasks, AuditService audit) {
        this.db = db;
        this.json = json;
        this.projects = projects;
        this.roles = roles;
        this.shifts = shifts;
        this.tasks = tasks;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- читання

    public ShiftReportView get(UUID shiftId) {
        WorkShiftView shift = shifts.get(shiftId);
        ProjectView project = projects.get(shift.projectId());
        Optional<ReportRow> report = row(shiftId);
        UUID reportId = report.map(ReportRow::id).orElse(null);

        List<TimeEntryView> times = reportId == null ? List.of() : times(reportId);
        List<IntervalView> intervals = reportId == null ? List.of() : intervals(reportId);
        List<QuantityView> quantities = reportId == null ? List.of() : quantities(reportId);
        List<ConsumableView> consumables = reportId == null ? List.of() : consumables(reportId);
        List<MeterView> meters = meters(shift, reportId);
        Map<UUID, BigDecimal> lastDepth = lastDepths(shift);

        List<ReportIssue> errors = timeErrors(times, shift.durationMinutes());
        List<ReportIssue> warnings = warnings(intervals, times, meters, lastDepth);
        UUID reportTask = reportTaskId(shiftId).orElse(null);
        String status = report.map(ReportRow::status).orElse("new");
        boolean open = "active".equals(project.status());
        boolean onSite = manage() || roles.isSupervisor(project);
        boolean assignee = reportTask != null && tasks.find(reportTask)
                .map(t -> t.assigneeId() != null && t.assigneeId().equals(currentUserId())).orElse(false);
        return new ShiftReportView(shiftId, reportId, status, shift.date(), shift.startsAt(), shift.durationMinutes(),
                report.map(ReportRow::notes).orElse(null), times, intervals, quantities, consumables, meters,
                totals(times, intervals), errors, warnings, reportId == null ? prefill(shift, lastDepth) : List.of(),
                open && !"submitted".equals(status) && (onSite || assignee),
                open && "submitted".equals(status) && onSite,
                reportTask, report.map(ReportRow::submittedAt).orElse(null),
                report.map(ReportRow::submittedByName).orElse(null), report.map(ReportRow::version).orElse(0));
    }

    // ---------------------------------------------------------------- зміни

    @Transactional
    public ShiftReportView save(UUID shiftId, ShiftReportRequest request) {
        WorkShiftView shift = shifts.get(shiftId);
        ProjectView project = openProject(shift.projectId());
        requireEditor(project, shiftId);
        Optional<ReportRow> existing = row(shiftId);
        if (existing.map(r -> "submitted".equals(r.status())).orElse(false)) {
            throw ApiException.conflict("report_submitted");
        }
        int version = existing.map(ReportRow::version).orElse(0);
        if (request.version() != version) {
            throw ApiException.conflict("stale_version");
        }
        validate(shift, request);

        UUID companyId = TenantContext.requireCompanyId();
        UUID reportId;
        if (existing.isEmpty()) {
            reportId = db.sql("""
                            insert into shift_report (company_id, shift_id, notes, created_by)
                            values (?, ?, ?, ?)
                            returning id
                            """)
                    .params(companyId, shiftId, blankToNull(request.notes()), currentUserId())
                    .query(UUID.class).single();
        } else {
            reportId = existing.get().id();
            int updated = db.sql("""
                            update shift_report set notes = ?, version = version + 1, updated_at = now()
                            where id = ? and version = ? and status = 'draft'
                            """)
                    .params(blankToNull(request.notes()), reportId, version).update();
            if (updated == 0) {
                throw ApiException.conflict("stale_version");
            }
            for (String table : List.of("shift_report_time", "shift_report_interval", "shift_report_quantity",
                    "shift_report_consumable", "shift_report_meter")) {
                db.sql("delete from " + table + " where report_id = ?").param(reportId).update();
            }
        }
        insertRows(companyId, reportId, request);
        audit.record("shift_report", reportId, existing.isEmpty() ? "create" : "update",
                Map.of("shiftId", shiftId, "times", request.times().size(), "intervals", request.intervals().size()));
        // Чернетка: задача рапорту «в роботі», якщо чек-лист уже не блокує
        reportTaskId(shiftId).ifPresent(task -> tasks.moveToStatus(task, "draft", "active", false));
        return get(shiftId);
    }

    @Transactional
    public ShiftReportView submit(UUID shiftId, int version) {
        WorkShiftView shift = shifts.get(shiftId);
        ProjectView project = openProject(shift.projectId());
        requireEditor(project, shiftId);
        ReportRow report = row(shiftId).orElseThrow(() -> ApiException.conflict("report_not_saved"));
        if (!"draft".equals(report.status())) {
            throw ApiException.conflict("report_submitted");
        }
        if (report.version() != version) {
            throw ApiException.conflict("stale_version");
        }
        if (!timeErrors(times(report.id()), shift.durationMinutes()).isEmpty()) {
            throw ApiException.conflict("report_incomplete");
        }
        Optional<UUID> reportTask = reportTaskId(shiftId);
        if (reportTask.flatMap(tasks::find).map(TaskView::openBlockers).orElse(0) > 0) {
            throw ApiException.conflict("checklist_required");
        }
        db.sql("""
                        update shift_report set status = 'submitted', submitted_at = now(), submitted_by = ?,
                                                version = version + 1, updated_at = now()
                        where id = ?
                        """)
                .params(currentUserId(), report.id()).update();
        reportTask.ifPresent(task -> tasks.moveToStatus(task, "submitted", "active", true));
        updateEngineHours(report.id());
        audit.record("shift_report", report.id(), "submit", Map.of("shiftId", shiftId));
        return get(shiftId);
    }

    /** Подано → чернетка: менеджер або супервайзер проєкту (до перевірки замовником у кроці 6). */
    @Transactional
    public ShiftReportView reopen(UUID shiftId, int version) {
        WorkShiftView shift = shifts.get(shiftId);
        ProjectView project = openProject(shift.projectId());
        if (!manage() && !roles.isSupervisor(project)) {
            throw forbidden();
        }
        ReportRow report = row(shiftId).orElseThrow(ApiException::notFound);
        if (!"submitted".equals(report.status())) {
            throw ApiException.conflict("report_not_submitted");
        }
        if (report.version() != version) {
            throw ApiException.conflict("stale_version");
        }
        db.sql("""
                        update shift_report set status = 'draft', submitted_at = null, submitted_by = null,
                                                version = version + 1, updated_at = now()
                        where id = ?
                        """)
                .param(report.id()).update();
        reportTaskId(shiftId).ifPresent(task -> tasks.moveToStatus(task, "draft", "active", false));
        audit.record("shift_report", report.id(), "reopen", Map.of("shiftId", shiftId));
        return get(shiftId);
    }

    /** Подано — бригаду зміни вже не змінити. */
    public boolean isSubmitted(UUID shiftId) {
        return row(shiftId).map(r -> "submitted".equals(r.status())).orElse(false);
    }

    // ---------------------------------------------------------------- перевірки

    private void validate(WorkShiftView shift, ShiftReportRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (int i = 0; i < request.times().size(); i++) {
            TimeEntryRequest time = request.times().get(i);
            String prefix = "times[" + i + "].";
            if (time.startMinute() < 0 || time.startMinute() >= shift.durationMinutes()) {
                errors.put(prefix + "startMinute", "outside_shift");
            }
            if (time.endMinute() <= time.startMinute()) {
                errors.put(prefix + "endMinute", "before_start");
            } else if (time.endMinute() > shift.durationMinutes()) {
                errors.put(prefix + "endMinute", "outside_shift");
            }
            Optional<Boolean> archived = db.sql("select archived_at is not null from activity_code where id = ?")
                    .param(time.activityCodeId()).query(Boolean.class).optional();
            if (archived.isEmpty()) {
                errors.put(prefix + "activityCodeId", "not_found");
            } else if (archived.get()) {
                errors.put(prefix + "activityCodeId", "archived");
            }
            if (time.taskId() != null && !exists("select 1 from task where id = ? and project_id = ?", time.taskId(),
                    shift.projectId())) {
                errors.put(prefix + "taskId", "not_found");
            }
        }
        for (int i = 0; i < request.intervals().size(); i++) {
            IntervalRequest interval = request.intervals().get(i);
            String prefix = "intervals[" + i + "].";
            if (!exists("select 1 from service where id = ? and project_id = ?", interval.serviceId(), shift.projectId())) {
                errors.put(prefix + "serviceId", "not_found");
            }
            if (interval.depthFrom().signum() < 0) {
                errors.put(prefix + "depthFrom", "too_small");
            }
            if (interval.depthTo().compareTo(interval.depthFrom()) <= 0) {
                errors.put(prefix + "depthTo", "before_from");
            }
            if (interval.coreLength() != null && interval.coreLength().signum() < 0) {
                errors.put(prefix + "coreLength", "too_small");
            }
        }
        for (int i = 0; i < request.quantities().size(); i++) {
            QuantityRequest quantity = request.quantities().get(i);
            String prefix = "quantities[" + i + "].";
            Optional<String> unit = db.sql("""
                            select coalesce(st.unit, '') from service s join service_type st on st.id = s.service_type_id
                            where s.id = ? and s.project_id = ?
                            """)
                    .params(quantity.serviceId(), shift.projectId()).query(String.class).optional();
            if (unit.isEmpty()) {
                errors.put(prefix + "serviceId", "not_found");
            } else if ("m".equals(unit.get())) {
                errors.put(prefix + "serviceId", "use_intervals");
            }
            if (quantity.quantity().signum() <= 0) {
                errors.put(prefix + "quantity", "too_small");
            }
        }
        for (int i = 0; i < request.consumables().size(); i++) {
            ConsumableRequest consumable = request.consumables().get(i);
            String prefix = "consumables[" + i + "].";
            if (!exists("select 1 from consumable_item where id = ?", consumable.itemId())) {
                errors.put(prefix + "itemId", "not_found");
            }
            if (consumable.quantity().signum() <= 0) {
                errors.put(prefix + "quantity", "too_small");
            }
        }
        Set<UUID> meterAssets = new HashSet<>(meterAssets(shift.assetId()).keySet());
        Set<UUID> seen = new HashSet<>();
        for (int i = 0; i < request.meters().size(); i++) {
            MeterRequest meter = request.meters().get(i);
            String prefix = "meters[" + i + "].";
            if (!meterAssets.contains(meter.assetId())) {
                errors.put(prefix + "assetId", "not_found");
            } else if (!seen.add(meter.assetId())) {
                errors.put(prefix + "assetId", "duplicate");
            }
            if (meter.startValue().signum() < 0) {
                errors.put(prefix + "startValue", "too_small");
            }
            if (meter.endValue().compareTo(meter.startValue()) < 0) {
                errors.put(prefix + "endValue", "before_start");
            }
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
    }

    /** Журнал часу: без проміжків і перекриттів від 0 до тривалості зміни. */
    static List<ReportIssue> timeErrors(List<TimeEntryView> times, int duration) {
        List<ReportIssue> issues = new ArrayList<>();
        if (times.isEmpty()) {
            issues.add(new ReportIssue("time_empty", Map.of()));
            return issues;
        }
        List<TimeEntryView> sorted = times.stream().sorted(Comparator.comparingInt(TimeEntryView::startMinute)).toList();
        int cursor = 0;
        int total = 0;
        for (TimeEntryView time : sorted) {
            if (time.startMinute() > cursor) {
                issues.add(new ReportIssue("time_gap", Map.of("from", String.valueOf(cursor), "to", String.valueOf(time.startMinute()))));
            } else if (time.startMinute() < cursor) {
                issues.add(new ReportIssue("time_overlap", Map.of("from", String.valueOf(time.startMinute()),
                        "to", String.valueOf(Math.min(cursor, time.endMinute())))));
            }
            cursor = Math.max(cursor, time.endMinute());
            total += time.endMinute() - time.startMinute();
        }
        if (cursor < duration) {
            issues.add(new ReportIssue("time_gap", Map.of("from", String.valueOf(cursor), "to", String.valueOf(duration))));
        }
        if (total != duration) {
            issues.add(new ReportIssue("time_total", Map.of("expected", String.valueOf(duration), "actual", String.valueOf(total))));
        }
        return issues;
    }

    private List<ReportIssue> warnings(List<IntervalView> intervals, List<TimeEntryView> times, List<MeterView> meters,
                                       Map<UUID, BigDecimal> lastDepth) {
        List<ReportIssue> warnings = new ArrayList<>();
        Map<UUID, BigDecimal> cursor = new HashMap<>(lastDepth);
        Map<UUID, String> codes = new LinkedHashMap<>();
        for (IntervalView interval : intervals) {
            codes.put(interval.serviceId(), interval.serviceCode());
            BigDecimal expected = cursor.get(interval.serviceId());
            if (expected != null && expected.compareTo(interval.depthFrom()) != 0) {
                warnings.add(new ReportIssue("depth_gap", Map.of("service", interval.serviceCode(),
                        "expected", expected.stripTrailingZeros().toPlainString(),
                        "actual", interval.depthFrom().stripTrailingZeros().toPlainString())));
            }
            cursor.put(interval.serviceId(), interval.depthTo());
            if (interval.recovery() != null && interval.recovery().compareTo(HUNDRED) > 0) {
                warnings.add(new ReportIssue("core_over_100", Map.of("service", interval.serviceCode(),
                        "recovery", interval.recovery().toPlainString())));
            }
        }
        for (MeterView meter : meters) {
            if (meter.previousEnd() != null && meter.startValue() != null
                    && meter.previousEnd().compareTo(meter.startValue()) != 0) {
                warnings.add(new ReportIssue("meter_mismatch", Map.of("asset", meter.assetCode(),
                        "expected", meter.previousEnd().stripTrailingZeros().toPlainString(),
                        "actual", meter.startValue().stripTrailingZeros().toPlainString())));
            }
        }
        // Інтервали по свердловині, а в журналі немає часу на її задачу
        Set<UUID> timeTasks = new HashSet<>();
        times.forEach(t -> {
            if (t.taskId() != null) {
                timeTasks.add(t.taskId());
            }
        });
        for (Map.Entry<UUID, String> service : codes.entrySet()) {
            List<UUID> serviceTasks = db.sql("select id from task where service_id = ?").param(service.getKey())
                    .query(UUID.class).list();
            if (serviceTasks.stream().noneMatch(timeTasks::contains)) {
                warnings.add(new ReportIssue("interval_without_time", Map.of("service", service.getValue())));
            }
        }
        return warnings;
    }

    private static ReportTotals totals(List<TimeEntryView> times, List<IntervalView> intervals) {
        int billable = 0;
        int nonBillable = 0;
        Map<String, CodeTotal> byCode = new LinkedHashMap<>();
        for (TimeEntryView time : times) {
            int minutes = time.endMinute() - time.startMinute();
            if (time.billable()) {
                billable += minutes;
            } else {
                nonBillable += minutes;
            }
            CodeTotal current = byCode.get(time.activityCode());
            byCode.put(time.activityCode(), new CodeTotal(time.activityCode(), time.activityName(), time.category(),
                    time.billable(), (current == null ? 0 : current.minutes()) + minutes));
        }
        BigDecimal meters = BigDecimal.ZERO;
        BigDecimal core = BigDecimal.ZERO;
        for (IntervalView interval : intervals) {
            meters = meters.add(interval.meters());
            if (interval.coreLength() != null) {
                core = core.add(interval.coreLength());
            }
        }
        return new ReportTotals(billable, nonBillable, new ArrayList<>(byCode.values()), meters, core,
                recovery(core, meters));
    }

    private static BigDecimal recovery(BigDecimal core, BigDecimal meters) {
        if (core == null || meters.signum() == 0) {
            return null;
        }
        return core.multiply(HUNDRED).divide(meters, 1, RoundingMode.HALF_UP);
    }

    // ---------------------------------------------------------------- дані

    private Optional<ReportRow> row(UUID shiftId) {
        return db.sql("""
                        select r.id, r.status, r.notes, r.version, r.submitted_at, u.full_name
                        from shift_report r left join app_user u on u.id = r.submitted_by
                        where r.shift_id = ?
                        """)
                .param(shiftId)
                .query((rs, n) -> new ReportRow(rs.getObject("id", UUID.class), rs.getString("status"), rs.getString("notes"),
                        rs.getInt("version"), rs.getObject("submitted_at", OffsetDateTime.class), rs.getString("full_name")))
                .optional();
    }

    private List<TimeEntryView> times(UUID reportId) {
        return db.sql("""
                        select r.start_minute, r.end_minute, r.activity_code_id, ac.code, ac.name, ac.category, ac.billable,
                               r.task_id, t.number, t.title, r.comment
                        from shift_report_time r
                                 join activity_code ac on ac.id = r.activity_code_id
                                 left join task t on t.id = r.task_id
                        where r.report_id = ?
                        order by r.start_minute, r.sort_order
                        """)
                .param(reportId)
                .query((rs, n) -> new TimeEntryView(rs.getInt("start_minute"), rs.getInt("end_minute"),
                        rs.getObject("activity_code_id", UUID.class), rs.getString("code"),
                        json.readValue(rs.getString("name"), NAME), rs.getString("category"), rs.getBoolean("billable"),
                        rs.getObject("task_id", UUID.class), rs.getObject("number") == null ? null : rs.getLong("number"),
                        rs.getString("title"), rs.getString("comment")))
                .list();
    }

    private List<IntervalView> intervals(UUID reportId) {
        return db.sql("""
                        select i.service_id, s.code, i.depth_from, i.depth_to, i.core_size, i.core_length, i.comment
                        from shift_report_interval i join service s on s.id = i.service_id
                        where i.report_id = ?
                        order by i.sort_order
                        """)
                .param(reportId)
                .query((rs, n) -> {
                    BigDecimal from = rs.getBigDecimal("depth_from");
                    BigDecimal to = rs.getBigDecimal("depth_to");
                    BigDecimal meters = to.subtract(from);
                    BigDecimal core = rs.getBigDecimal("core_length");
                    return new IntervalView(rs.getObject("service_id", UUID.class), rs.getString("code"), from, to, meters,
                            rs.getString("core_size"), core, recovery(core, meters), rs.getString("comment"));
                })
                .list();
    }

    private List<QuantityView> quantities(UUID reportId) {
        return db.sql("""
                        select q.service_id, s.code, st.unit, q.quantity, q.comment
                        from shift_report_quantity q
                                 join service s on s.id = q.service_id
                                 join service_type st on st.id = s.service_type_id
                        where q.report_id = ?
                        order by q.sort_order
                        """)
                .param(reportId)
                .query((rs, n) -> new QuantityView(rs.getObject("service_id", UUID.class), rs.getString("code"),
                        rs.getString("unit"), rs.getBigDecimal("quantity"), rs.getString("comment")))
                .list();
    }

    private List<ConsumableView> consumables(UUID reportId) {
        return db.sql("""
                        select c.item_id, i.code, i.name, i.unit, c.quantity, c.comment
                        from shift_report_consumable c join consumable_item i on i.id = c.item_id
                        where c.report_id = ?
                        order by c.sort_order
                        """)
                .param(reportId)
                .query((rs, n) -> new ConsumableView(rs.getObject("item_id", UUID.class), rs.getString("code"),
                        json.readValue(rs.getString("name"), NAME), rs.getString("unit"), rs.getBigDecimal("quantity"),
                        rs.getString("comment")))
                .list();
    }

    /** Лічильники бурової й вкладеного обладнання: збережені значення або початок = кінець попередньої зміни. */
    private List<MeterView> meters(WorkShiftView shift, UUID reportId) {
        Map<UUID, String> assets = meterAssets(shift.assetId());
        Map<UUID, BigDecimal[]> saved = new HashMap<>();
        if (reportId != null) {
            db.sql("select asset_id, start_value, end_value from shift_report_meter where report_id = ?").param(reportId)
                    .query((rs, n) -> saved.put(rs.getObject("asset_id", UUID.class),
                            new BigDecimal[] {rs.getBigDecimal("start_value"), rs.getBigDecimal("end_value")}))
                    .list();
        }
        List<MeterView> result = new ArrayList<>();
        for (Map.Entry<UUID, String> asset : assets.entrySet()) {
            BigDecimal previous = db.sql("""
                            select m.end_value
                            from shift_report_meter m
                                     join shift_report r on r.id = m.report_id
                                     join work_shift s on s.id = r.shift_id
                            where m.asset_id = ? and s.starts_at < ?
                            order by s.starts_at desc
                            limit 1
                            """)
                    .params(asset.getKey(), shift.startsAt()).query(BigDecimal.class).optional().orElse(null);
            BigDecimal[] values = saved.get(asset.getKey());
            result.add(new MeterView(asset.getKey(), asset.getValue(), values != null ? values[0] : previous,
                    values != null ? values[1] : null, previous));
        }
        return result;
    }

    private Map<UUID, String> meterAssets(UUID rigId) {
        Map<UUID, String> assets = new LinkedHashMap<>();
        db.sql("""
                        select id, code from asset
                        where (id = ? or parent_id = ?) and archived_at is null
                        order by parent_id nulls first, code
                        """)
                .params(rigId, rigId)
                .query((rs, n) -> assets.put(rs.getObject("id", UUID.class), rs.getString("code")))
                .list();
        return assets;
    }

    /** Останнє «до» по кожній свердловині з рапортів попередніх змін (будь-якої бурової). */
    private Map<UUID, BigDecimal> lastDepths(WorkShiftView shift) {
        Map<UUID, BigDecimal> result = new HashMap<>();
        db.sql("""
                        select i.service_id, max(i.depth_to) as depth
                        from shift_report_interval i
                                 join shift_report r on r.id = i.report_id
                                 join work_shift s on s.id = r.shift_id
                        where s.project_id = ? and s.starts_at < ?
                        group by i.service_id
                        """)
                .params(shift.projectId(), shift.startsAt())
                .query((rs, n) -> result.put(rs.getObject("service_id", UUID.class), rs.getBigDecimal("depth")))
                .list();
        return result;
    }

    /** Новий рапорт: свердловини з попереднього рапорту цієї бурової, що ще не завершені, від останнього «до». */
    private List<IntervalStart> prefill(WorkShiftView shift, Map<UUID, BigDecimal> lastDepth) {
        return db.sql("""
                        select distinct i.service_id, sv.code
                        from shift_report_interval i
                                 join service sv on sv.id = i.service_id
                        where sv.status in ('planned', 'in_progress')
                          and i.report_id = (select r.id from shift_report r join work_shift s on s.id = r.shift_id
                                             where s.asset_id = ? and s.starts_at < ?
                                               and exists (select 1 from shift_report_interval x where x.report_id = r.id)
                                             order by s.starts_at desc limit 1)
                        """)
                .params(shift.assetId(), shift.startsAt())
                .query((rs, n) -> {
                    UUID service = rs.getObject("service_id", UUID.class);
                    return new IntervalStart(service, rs.getString("code"), lastDepth.getOrDefault(service, BigDecimal.ZERO));
                })
                .list();
    }

    private void insertRows(UUID companyId, UUID reportId, ShiftReportRequest request) {
        for (int i = 0; i < request.times().size(); i++) {
            TimeEntryRequest time = request.times().get(i);
            db.sql("""
                            insert into shift_report_time (company_id, report_id, start_minute, end_minute, activity_code_id,
                                                           task_id, comment, sort_order)
                            values (?, ?, ?, ?, ?, ?, ?, ?)
                            """)
                    .params(companyId, reportId, time.startMinute(), time.endMinute(), time.activityCodeId(), time.taskId(),
                            blankToNull(time.comment()), i)
                    .update();
        }
        for (int i = 0; i < request.intervals().size(); i++) {
            IntervalRequest interval = request.intervals().get(i);
            db.sql("""
                            insert into shift_report_interval (company_id, report_id, service_id, depth_from, depth_to,
                                                               core_size, core_length, comment, sort_order)
                            values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """)
                    .params(companyId, reportId, interval.serviceId(), interval.depthFrom(), interval.depthTo(),
                            blankToNull(interval.coreSize()), interval.coreLength(), blankToNull(interval.comment()), i)
                    .update();
        }
        for (int i = 0; i < request.quantities().size(); i++) {
            QuantityRequest quantity = request.quantities().get(i);
            db.sql("""
                            insert into shift_report_quantity (company_id, report_id, service_id, quantity, comment, sort_order)
                            values (?, ?, ?, ?, ?, ?)
                            """)
                    .params(companyId, reportId, quantity.serviceId(), quantity.quantity(), blankToNull(quantity.comment()), i)
                    .update();
        }
        for (int i = 0; i < request.consumables().size(); i++) {
            ConsumableRequest consumable = request.consumables().get(i);
            db.sql("""
                            insert into shift_report_consumable (company_id, report_id, item_id, quantity, comment, sort_order)
                            values (?, ?, ?, ?, ?, ?)
                            """)
                    .params(companyId, reportId, consumable.itemId(), consumable.quantity(),
                            blankToNull(consumable.comment()), i)
                    .update();
        }
        for (MeterRequest meter : request.meters()) {
            db.sql("""
                            insert into shift_report_meter (company_id, report_id, asset_id, start_value, end_value)
                            values (?, ?, ?, ?, ?)
                            """)
                    .params(companyId, reportId, meter.assetId(), meter.startValue(), meter.endValue()).update();
        }
    }

    /** Мотогодини з поданого рапорту — у поле engineHours обладнання (якщо воно є в типі й значення більше). */
    private void updateEngineHours(UUID reportId) {
        db.sql("""
                        update asset a
                        set attributes = jsonb_set(a.attributes, '{engineHours}', to_jsonb(m.end_value)),
                            version    = a.version + 1,
                            updated_at = now()
                        from shift_report_meter m, asset_type t
                        where m.report_id = ?
                          and a.id = m.asset_id
                          and t.id = a.asset_type_id
                          and exists (select 1 from jsonb_array_elements(t.fields) f where f ->> 'key' = 'engineHours')
                          and coalesce((a.attributes ->> 'engineHours')::numeric, 0) < m.end_value
                        """)
                .param(reportId).update();
    }

    private Optional<UUID> reportTaskId(UUID shiftId) {
        return db.sql("""
                        select t.id from task t join task_type tt on tt.id = t.task_type_id
                        where t.work_shift_id = ? and tt.code = ?
                        order by t.number limit 1
                        """)
                .params(shiftId, REPORT_TASK_TYPE).query(UUID.class).optional();
    }

    private void requireEditor(ProjectView project, UUID shiftId) {
        if (manage() || roles.isSupervisor(project)) {
            return;
        }
        UUID user = currentUserId();
        boolean assignee = reportTaskId(shiftId).flatMap(tasks::find)
                .map(t -> user != null && user.equals(t.assigneeId())).orElse(false);
        if (!assignee) {
            throw forbidden();
        }
    }

    private boolean exists(String sql, Object... params) {
        return db.sql("select exists (" + sql + ")").params(params).query(Boolean.class).single();
    }

    private static boolean manage() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return false;
        }
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if ("project.manage".equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    private ProjectView openProject(UUID projectId) {
        ProjectView project = projects.get(projectId);
        if ("closed".equals(project.status())) {
            throw ApiException.conflict("project_closed");
        }
        return project;
    }

    private static ApiException forbidden() {
        return new ApiException(HttpStatus.FORBIDDEN, "forbidden", Map.of());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static UUID currentUserId() {
        return TenantContext.current().map(TenantContext.Tenant::userId).orElse(null);
    }
}
