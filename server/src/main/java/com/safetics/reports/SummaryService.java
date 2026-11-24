package com.safetics.reports;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.safetics.core.web.ApiException;
import com.safetics.reports.SummaryDtos.CategoryMinutes;
import com.safetics.reports.SummaryDtos.CodeMinutes;
import com.safetics.reports.SummaryDtos.DepthBand;
import com.safetics.reports.SummaryDtos.ItemQuantity;
import com.safetics.reports.SummaryDtos.SummaryRow;
import com.safetics.reports.SummaryDtos.SummaryView;
import com.safetics.reports.SummaryDtos.UnitQuantity;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Зведені звіти: обладнання × період (місяць / півріччя / рік) з підсумком. Рахуються запитами над
 * змінними рапортами, окремих таблиць немає. Зміна належить періоду дати свого початку. За
 * замовчуванням — лише подані рапорти. Видимість — RLS змін (task.view, проєкти користувача).
 */
@Service
public class SummaryService {

    static final Set<String> PERIODS = Set.of("month", "half_year", "year");
    /** Найдовший діапазон дат звіту. */
    static final int MAX_YEARS = 5;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final List<String> CATEGORIES = List.of("productive", "contractor_npt", "client_standby", "other");
    private static final TypeReference<Map<String, String>> NAME = new TypeReference<>() {
    };

    /** Накопичувач для рядка обладнання × період. */
    private static final class Acc {
        final UUID assetId;
        String code;
        String name;
        final LocalDate start;
        long shifts;
        long submitted;
        long draft;
        long missing;
        long billable;
        long nonBillable;
        final Map<String, Long> categories = new LinkedHashMap<>();
        final Map<String, CodeMinutes> codes = new LinkedHashMap<>();
        BigDecimal meters = BigDecimal.ZERO;
        BigDecimal core = BigDecimal.ZERO;
        final TreeMap<BigDecimal, BigDecimal> bands = new TreeMap<>();
        final Map<String, BigDecimal> quantities = new LinkedHashMap<>();
        long completed;
        final Map<UUID, ItemQuantity> items = new LinkedHashMap<>();

        Acc(UUID assetId, LocalDate start) {
            this.assetId = assetId;
            this.start = start;
        }
    }

    private final JdbcClient db;
    private final JsonMapper json;

    public SummaryService(JdbcClient db, JsonMapper json) {
        this.db = db;
        this.json = json;
    }

    @Transactional
    public SummaryView summary(UUID projectId, UUID assetId, LocalDate from, LocalDate to, String period, int depthStep,
                               boolean includeDrafts) {
        if (!PERIODS.contains(period)) {
            throw ApiException.validation("period", "invalid_option");
        }
        if (from.isAfter(to)) {
            throw ApiException.validation("from", "after_to");
        }
        if (from.plusYears(MAX_YEARS).isBefore(to)) {
            throw ApiException.validation("to", "too_large");
        }
        if (depthStep < 1 || depthStep > 10_000) {
            throw ApiException.validation("depthStep", "invalid_option");
        }
        // Портал (без task.view): лише проєкти свого замовника (їх бачить через RLS), зміни читаються
        // від імені системи, у звіті — лише метри, оплачувані години й обсяги
        boolean portal = !hasAuthority("task.view");
        String visible = "";
        if (portal) {
            List<UUID> projects = db.sql("select id from project").query(UUID.class).list();
            if (projects.isEmpty() || (projectId != null && !projects.contains(projectId))) {
                SummaryRow empty = row(new Acc(null, null), period, BigDecimal.valueOf(depthStep));
                return new SummaryView(period, from, to, depthStep, false, List.of(), empty);
            }
            includeDrafts = false;
            visible = projects.stream().map(id -> "'" + id + "'").collect(java.util.stream.Collectors.joining(", "));
            db.sql("select set_config('app.system', 'on', true)").query(String.class).single();
        }
        // Подані (зокрема перевірені й виправлені замовником); чернетки — також повернуті замовником
        String statuses = includeDrafts ? "('submitted', 'accepted', 'corrected', 'draft', 'returned')"
                : "('submitted', 'accepted', 'corrected')";
        String where = "s.shift_date between :from and :to"
                + (projectId != null ? " and s.project_id = :project" : "")
                + (assetId != null ? " and s.asset_id = :asset" : "")
                + (portal ? " and s.project_id in (" + visible + ")" : "");
        Map<String, Acc> rows = new LinkedHashMap<>();
        BigDecimal step = BigDecimal.valueOf(depthStep);

        // Зміни й стан рапортів
        query("""
                select s.asset_id, a.code, a.name, %s as bucket, count(*) as shifts,
                       count(*) filter (where r.status in ('submitted', 'accepted', 'corrected')) as submitted,
                       count(*) filter (where r.status in ('draft', 'returned')) as draft,
                       count(*) filter (where r.id is null) as missing
                from work_shift s
                         join asset a on a.id = s.asset_id
                         left join shift_report r on r.shift_id = s.id
                where %s
                group by 1, 2, 3, 4
                """.formatted(bucket(period, "s.shift_date"), where), projectId, assetId, from, to)
                .query((rs, n) -> {
                    Acc acc = acc(rows, rs.getObject("asset_id", UUID.class), rs.getObject("bucket", LocalDate.class));
                    acc.code = rs.getString("code");
                    acc.name = rs.getString("name");
                    acc.shifts = rs.getLong("shifts");
                    acc.submitted = rs.getLong("submitted");
                    acc.draft = rs.getLong("draft");
                    acc.missing = rs.getLong("missing");
                    return null;
                }).list();

        // Журнал часу за кодами
        query("""
                select s.asset_id, %s as bucket, ac.code, ac.name, ac.category, ac.billable,
                       sum(t.end_minute - t.start_minute) as minutes
                from work_shift s
                         join shift_report r on r.shift_id = s.id
                         join shift_report_time t on t.report_id = r.id
                         join activity_code ac on ac.id = t.activity_code_id
                where %s and r.status in %s
                group by 1, 2, 3, 4, 5, 6
                order by 3
                """.formatted(bucket(period, "s.shift_date"), where, statuses), projectId, assetId, from, to)
                .query((rs, n) -> {
                    Acc acc = acc(rows, rs.getObject("asset_id", UUID.class), rs.getObject("bucket", LocalDate.class));
                    long minutes = rs.getLong("minutes");
                    boolean billable = rs.getBoolean("billable");
                    String category = rs.getString("category");
                    if (billable) {
                        acc.billable += minutes;
                    } else {
                        acc.nonBillable += minutes;
                    }
                    acc.categories.merge(category, minutes, Long::sum);
                    String code = rs.getString("code");
                    acc.codes.put(code, new CodeMinutes(code, json.readValue(rs.getString("name"), NAME), category, billable,
                            minutes + (acc.codes.containsKey(code) ? acc.codes.get(code).minutes() : 0)));
                    return null;
                }).list();

        // Інтервали: метри, керн, діапазони глибини
        query("""
                select s.asset_id, %s as bucket, i.depth_from, i.depth_to, i.core_length
                from work_shift s
                         join shift_report r on r.shift_id = s.id
                         join shift_report_interval i on i.report_id = r.id
                where %s and r.status in %s
                """.formatted(bucket(period, "s.shift_date"), where, statuses), projectId, assetId, from, to)
                .query((rs, n) -> {
                    Acc acc = acc(rows, rs.getObject("asset_id", UUID.class), rs.getObject("bucket", LocalDate.class));
                    BigDecimal depthFrom = rs.getBigDecimal("depth_from");
                    BigDecimal depthTo = rs.getBigDecimal("depth_to");
                    acc.meters = acc.meters.add(depthTo.subtract(depthFrom));
                    BigDecimal core = rs.getBigDecimal("core_length");
                    if (core != null) {
                        acc.core = acc.core.add(core);
                    }
                    split(depthFrom, depthTo, step).forEach((band, meters) -> acc.bands.merge(band, meters, BigDecimal::add));
                    return null;
                }).list();

        // Обсяги за одиницями
        query("""
                select s.asset_id, %s as bucket, coalesce(st.unit, '') as unit, sum(q.quantity) as quantity
                from work_shift s
                         join shift_report r on r.shift_id = s.id
                         join shift_report_quantity q on q.report_id = r.id
                         join service sv on sv.id = q.service_id
                         join service_type st on st.id = sv.service_type_id
                where %s and r.status in %s
                group by 1, 2, 3
                """.formatted(bucket(period, "s.shift_date"), where, statuses), projectId, assetId, from, to)
                .query((rs, n) -> {
                    acc(rows, rs.getObject("asset_id", UUID.class), rs.getObject("bucket", LocalDate.class))
                            .quantities.merge(rs.getString("unit"), rs.getBigDecimal("quantity"), BigDecimal::add);
                    return null;
                }).list();

        // Витрати за позиціями
        query("""
                select s.asset_id, %s as bucket, c.item_id, i.code, i.name, i.unit, sum(c.quantity) as quantity
                from work_shift s
                         join shift_report r on r.shift_id = s.id
                         join shift_report_consumable c on c.report_id = r.id
                         join consumable_item i on i.id = c.item_id
                where %s and r.status in %s
                group by 1, 2, 3, 4, 5, 6
                order by 4
                """.formatted(bucket(period, "s.shift_date"), where, statuses), projectId, assetId, from, to)
                .query((rs, n) -> {
                    Acc acc = acc(rows, rs.getObject("asset_id", UUID.class), rs.getObject("bucket", LocalDate.class));
                    UUID item = rs.getObject("item_id", UUID.class);
                    acc.items.put(item, new ItemQuantity(item, rs.getString("code"), json.readValue(rs.getString("name"), NAME),
                            rs.getString("unit"), rs.getBigDecimal("quantity")));
                    return null;
                }).list();

        // Завершені послуги, на яких обладнання працювало за рапортами (інтервали або час на задачу послуги)
        String work = """
                select s.asset_id, i.service_id
                from work_shift s
                         join shift_report r on r.shift_id = s.id
                         join shift_report_interval i on i.report_id = r.id
                where r.status in %1$s
                union
                select s.asset_id, tk.service_id
                from work_shift s
                         join shift_report r on r.shift_id = s.id
                         join shift_report_time t on t.report_id = r.id
                         join task tk on tk.id = t.task_id
                where r.status in %1$s and tk.service_id is not null
                """.formatted(statuses);
        query("""
                select x.asset_id, a.code, a.name, %s as bucket, count(distinct x.service_id) as completed
                from (select w.asset_id, sv.id as service_id, (sv.actual_end at time zone p.time_zone)::date as done_date
                      from (%s) w
                               join service sv on sv.id = w.service_id
                               join project p on p.id = sv.project_id
                      where sv.status = 'completed' and sv.actual_end is not null%s) x
                         join asset a on a.id = x.asset_id
                where x.done_date between :from and :to%s
                group by 1, 2, 3, 4
                """.formatted(bucket(period, "x.done_date"), work,
                (projectId != null ? " and sv.project_id = :project" : "")
                        + (portal ? " and sv.project_id in (" + visible + ")" : ""),
                assetId != null ? " and x.asset_id = :asset" : ""), projectId, assetId, from, to)
                .query((rs, n) -> {
                    Acc acc = acc(rows, rs.getObject("asset_id", UUID.class), rs.getObject("bucket", LocalDate.class));
                    if (acc.code == null) {
                        acc.code = rs.getString("code");
                        acc.name = rs.getString("name");
                    }
                    acc.completed = rs.getLong("completed");
                    return null;
                }).list();

        List<Acc> sorted = new ArrayList<>(rows.values());
        sorted.sort(Comparator.comparing((Acc a) -> a.code == null ? "" : a.code).thenComparing(a -> a.start));
        Acc total = new Acc(null, null);
        for (Acc acc : sorted) {
            merge(total, acc);
        }
        if (portal) {
            db.sql("select set_config('app.system', '', true)").query(String.class).single();
            for (Acc acc : rows.values()) {
                forPortal(acc);
            }
            forPortal(total);
        }
        return new SummaryView(period, from, to, depthStep, includeDrafts,
                sorted.stream().map(a -> row(a, period, step)).toList(), row(total, period, step));
    }

    /** Для замовника: без неоплачуваних годин, витрат, стану рапортів і неоплачуваних кодів. */
    private static void forPortal(Acc acc) {
        acc.nonBillable = 0;
        acc.draft = 0;
        acc.missing = 0;
        acc.items.clear();
        acc.codes.values().removeIf(c -> !c.billable());
        acc.categories.clear();
    }

    private static boolean hasAuthority(String code) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getAuthorities().stream().anyMatch(a -> code.equals(a.getAuthority()));
    }

    /** Метри інтервалу [from, to) за діапазонами глибини кроку step: ключ — початок діапазону. */
    static Map<BigDecimal, BigDecimal> split(BigDecimal from, BigDecimal to, BigDecimal step) {
        Map<BigDecimal, BigDecimal> result = new LinkedHashMap<>();
        BigDecimal bandStart = from.divide(step, 0, RoundingMode.FLOOR).multiply(step);
        while (bandStart.compareTo(to) < 0) {
            BigDecimal bandEnd = bandStart.add(step);
            BigDecimal meters = to.min(bandEnd).subtract(from.max(bandStart));
            if (meters.signum() > 0) {
                result.put(bandStart.setScale(0, RoundingMode.UNNECESSARY), meters);
            }
            bandStart = bandEnd;
        }
        return result;
    }

    private JdbcClient.StatementSpec query(String sql, UUID projectId, UUID assetId, LocalDate from, LocalDate to) {
        JdbcClient.StatementSpec spec = db.sql(sql).param("from", from).param("to", to);
        if (projectId != null) {
            spec = spec.param("project", projectId);
        }
        if (assetId != null) {
            spec = spec.param("asset", assetId);
        }
        return spec;
    }

    private static String bucket(String period, String column) {
        return switch (period) {
            case "year" -> "date_trunc('year', " + column + ")::date";
            case "half_year" -> "make_date(extract(year from " + column + ")::int, case when extract(month from " + column
                    + ") <= 6 then 1 else 7 end, 1)";
            default -> "date_trunc('month', " + column + ")::date";
        };
    }

    private static Acc acc(Map<String, Acc> rows, UUID assetId, LocalDate start) {
        return rows.computeIfAbsent(assetId + "|" + start, k -> new Acc(assetId, start));
    }

    private static void merge(Acc total, Acc acc) {
        total.shifts += acc.shifts;
        total.submitted += acc.submitted;
        total.draft += acc.draft;
        total.missing += acc.missing;
        total.billable += acc.billable;
        total.nonBillable += acc.nonBillable;
        acc.categories.forEach((k, v) -> total.categories.merge(k, v, Long::sum));
        acc.codes.forEach((k, v) -> {
            CodeMinutes current = total.codes.get(k);
            total.codes.put(k, new CodeMinutes(v.code(), v.name(), v.category(), v.billable(),
                    v.minutes() + (current == null ? 0 : current.minutes())));
        });
        total.meters = total.meters.add(acc.meters);
        total.core = total.core.add(acc.core);
        acc.bands.forEach((k, v) -> total.bands.merge(k, v, BigDecimal::add));
        acc.quantities.forEach((k, v) -> total.quantities.merge(k, v, BigDecimal::add));
        total.completed += acc.completed;
        acc.items.forEach((k, v) -> {
            ItemQuantity current = total.items.get(k);
            total.items.put(k, new ItemQuantity(v.itemId(), v.code(), v.name(), v.unit(),
                    current == null ? v.quantity() : current.quantity().add(v.quantity())));
        });
    }

    private static SummaryRow row(Acc a, String period, BigDecimal step) {
        LocalDate end = a.start == null ? null : switch (period) {
            case "year" -> a.start.plusYears(1).minusDays(1);
            case "half_year" -> a.start.plusMonths(6).minusDays(1);
            default -> a.start.plusMonths(1).minusDays(1);
        };
        List<CategoryMinutes> categories = new ArrayList<>();
        for (String category : CATEGORIES) {
            if (a.categories.containsKey(category)) {
                categories.add(new CategoryMinutes(category, a.categories.get(category)));
            }
        }
        List<DepthBand> bands = a.bands.entrySet().stream()
                .map(e -> new DepthBand(e.getKey(), e.getKey().add(step), e.getValue().setScale(2, RoundingMode.HALF_UP)))
                .toList();
        BigDecimal recovery = a.meters.signum() == 0 ? null
                : a.core.multiply(HUNDRED).divide(a.meters, 1, RoundingMode.HALF_UP);
        return new SummaryRow(a.assetId, a.code, a.name, a.start, end, a.shifts, a.submitted, a.draft, a.missing,
                a.billable, a.nonBillable, categories, new ArrayList<>(a.codes.values()),
                a.meters.setScale(2, RoundingMode.HALF_UP), a.core.setScale(2, RoundingMode.HALF_UP), recovery, bands,
                a.quantities.entrySet().stream().map(e -> new UnitQuantity(e.getKey(), e.getValue())).toList(),
                a.completed, new ArrayList<>(a.items.values()));
    }
}
