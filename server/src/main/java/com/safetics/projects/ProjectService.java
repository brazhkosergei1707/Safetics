package com.safetics.projects;

import static com.safetics.customers.CustomerService.blankToNull;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.safetics.core.audit.AuditService;
import com.safetics.core.tenancy.TenantContext;
import com.safetics.core.web.ApiException;
import com.safetics.customers.CustomerDtos.CustomerView;
import com.safetics.customers.CustomerService;
import com.safetics.projects.ProjectDtos.ProjectCreateRequest;
import com.safetics.projects.ProjectDtos.ProjectUpdateRequest;
import com.safetics.projects.ProjectDtos.ProjectView;
import com.safetics.projects.ProjectDtos.Shift;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Проєкти компанії. Які проєкти бачить користувач, вирішує RLS (project.access_all, project_access,
 * контактна особа замовника), тому «чужий» проєкт тут просто не знаходиться — 404.
 *
 * <p>Автор проєкту без project.access_all одразу отримує доступ до нього, інакше він не побачив би
 * щойно створений проєкт.
 */
@Service
public class ProjectService {

    static final TypeReference<List<Shift>> SHIFTS = new TypeReference<>() {
    };

    /** Графік, що діє сьогодні: остання версія з датою ≤ сьогодні, інакше перша. */
    private static final String SELECT = """
            select p.id, p.code, p.name, p.customer_id, c.name as customer_name, p.location, p.country, p.time_zone,
                   p.start_date, p.end_date, p.status, p.version, coalesce(cs.shifts, '[]'::jsonb) as shifts
            from project p
                     left join customer c on c.id = p.customer_id
                     left join lateral (
                select s.shifts
                from shift_schedule s
                where s.project_id = p.id
                order by s.valid_from <= (now() at time zone p.time_zone)::date desc,
                         case when s.valid_from <= (now() at time zone p.time_zone)::date then s.valid_from end desc nulls last,
                         s.valid_from
                limit 1) cs on true
            """;

    public record Filter(String status, UUID customerId, String query) {
    }

    private final JdbcClient db;
    private final JsonMapper json;
    private final CustomerService customers;
    private final AuditService audit;
    private final Clock clock;

    public ProjectService(JdbcClient db, JsonMapper json, CustomerService customers, AuditService audit, Clock clock) {
        this.db = db;
        this.json = json;
        this.customers = customers;
        this.audit = audit;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- читання

    public List<ProjectView> list(Filter filter) {
        StringBuilder where = new StringBuilder(" where 1 = 1");
        List<Object> params = new ArrayList<>();
        if (filter.status() != null && !filter.status().isBlank()) {
            where.append(" and p.status = ?");
            params.add(filter.status());
        }
        if (filter.customerId() != null) {
            where.append(" and p.customer_id = ?");
            params.add(filter.customerId());
        }
        if (filter.query() != null && !filter.query().isBlank()) {
            String like = "%" + filter.query().trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            where.append(" and (p.code ilike ? or p.name ilike ? or c.name ilike ?)");
            params.add(like);
            params.add(like);
            params.add(like);
        }
        return db.sql(SELECT + where + " order by p.status, p.start_date desc, p.code")
                .params(params).query(this::map).list();
    }

    public ProjectView get(UUID id) {
        return find(id).orElseThrow(ApiException::notFound);
    }

    public Optional<ProjectView> find(UUID id) {
        return db.sql(SELECT + " where p.id = ?").param(id).query(this::map).optional();
    }

    /** Сьогоднішня дата за часовим поясом проєкту. */
    public LocalDate today(ProjectView project) {
        return LocalDate.now(clock.withZone(ZoneId.of(project.timeZone())));
    }

    // ---------------------------------------------------------------- зміни

    @Transactional
    public ProjectView create(ProjectCreateRequest request) {
        UUID companyId = TenantContext.requireCompanyId();
        String timeZone = timeZone(request.timeZone());
        String country = CustomerService.country(request.country());
        checkCustomer(request.customerId(), null);
        checkDates(request.startDate(), request.endDate());
        List<Shift> shifts = ShiftRules.validate(request.shifts());

        // Без returning: RLS не покаже новий рядок автору, поки він не має доступу
        UUID id = UUID.randomUUID();
        db.sql("""
                        insert into project (id, company_id, customer_id, code, name, location, country, time_zone,
                                             start_date, end_date)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(id, companyId, request.customerId(), request.code().trim(), request.name().trim(),
                        blankToNull(request.location()), country, timeZone, request.startDate(), request.endDate())
                .update();

        Boolean allProjects = db.sql("select app_all_projects()").query(Boolean.class).single();
        if (!Boolean.TRUE.equals(allProjects)) {
            UUID userId = TenantContext.current().map(TenantContext.Tenant::userId).orElseThrow();
            db.sql("insert into project_access (company_id, project_id, user_id, granted_by) values (?, ?, ?, ?)")
                    .params(companyId, id, userId, userId).update();
        }
        db.sql("""
                        insert into shift_schedule (company_id, project_id, valid_from, shifts, created_by)
                        values (?, ?, ?, cast(? as jsonb), ?)
                        """)
                .params(companyId, id, request.startDate(), json.writeValueAsString(shifts),
                        TenantContext.current().map(TenantContext.Tenant::userId).orElse(null))
                .update();

        audit.record("project", id, "create", Map.of("code", request.code().trim(), "name", request.name().trim()));
        return get(id);
    }

    public ProjectView update(UUID id, ProjectUpdateRequest request) {
        ProjectView existing = get(id);
        if (request.version() != existing.version()) {
            throw ApiException.conflict("stale_version");
        }
        String timeZone = timeZone(request.timeZone());
        String country = CustomerService.country(request.country());
        checkCustomer(request.customerId(), existing.customerId());
        checkDates(request.startDate(), request.endDate());

        int updated = db.sql("""
                        update project
                        set code = ?, name = ?, customer_id = ?, location = ?, country = ?, time_zone = ?,
                            start_date = ?, end_date = ?, version = version + 1, updated_at = now()
                        where id = ? and version = ?
                        """)
                .params(request.code().trim(), request.name().trim(), request.customerId(),
                        blankToNull(request.location()), country, timeZone, request.startDate(), request.endDate(),
                        id, request.version())
                .update();
        if (updated == 0) {
            throw ApiException.conflict("stale_version");
        }
        ProjectView saved = get(id);
        Map<String, Object> changes = new LinkedHashMap<>();
        compare(changes, "code", existing.code(), saved.code());
        compare(changes, "name", existing.name(), saved.name());
        compare(changes, "customerId", existing.customerId(), saved.customerId());
        compare(changes, "location", existing.location(), saved.location());
        compare(changes, "country", existing.country(), saved.country());
        compare(changes, "timeZone", existing.timeZone(), saved.timeZone());
        compare(changes, "startDate", existing.startDate(), saved.startDate());
        compare(changes, "endDate", existing.endDate(), saved.endDate());
        if (!changes.isEmpty()) {
            audit.record("project", id, "update", changes);
        }
        return saved;
    }

    /** Закриття: дата кінця — указана або сьогодні (не раніше дати початку). */
    public ProjectView close(UUID id) {
        ProjectView existing = get(id);
        if ("closed".equals(existing.status())) {
            return existing;
        }
        LocalDate endDate = existing.endDate();
        if (endDate == null) {
            LocalDate today = today(existing);
            endDate = today.isBefore(existing.startDate()) ? existing.startDate() : today;
        }
        db.sql("""
                        update project set status = 'closed', end_date = ?, version = version + 1, updated_at = now()
                        where id = ?
                        """)
                .params(endDate, id).update();
        audit.record("project", id, "close", Map.of("endDate", endDate.toString()));
        return get(id);
    }

    public ProjectView reopen(UUID id) {
        ProjectView existing = get(id);
        if ("active".equals(existing.status())) {
            return existing;
        }
        if (existing.customerId() != null && customers.get(existing.customerId()).archived()) {
            throw ApiException.conflict("customer_archived");
        }
        db.sql("update project set status = 'active', version = version + 1, updated_at = now() where id = ?")
                .param(id).update();
        audit.record("project", id, "reopen", null);
        return get(id);
    }

    // ---------------------------------------------------------------- правила

    private void checkCustomer(UUID customerId, UUID currentCustomerId) {
        if (customerId == null || customerId.equals(currentCustomerId)) {
            return;
        }
        CustomerView customer = customers.find(customerId)
                .orElseThrow(() -> ApiException.validation("customerId", "not_found"));
        if (customer.archived()) {
            throw ApiException.validation("customerId", "archived");
        }
    }

    private static void checkDates(LocalDate start, LocalDate end) {
        if (end != null && end.isBefore(start)) {
            throw ApiException.validation("endDate", "before_start");
        }
    }

    private static String timeZone(String raw) {
        try {
            String value = raw.trim();
            ZoneId zone = ZoneId.of(value);
            if (!zone.getId().contains("/") && !"UTC".equals(zone.getId())) {
                throw ApiException.validation("timeZone", "invalid_option");
            }
            return zone.getId();
        } catch (DateTimeException e) {
            throw ApiException.validation("timeZone", "invalid_option");
        }
    }

    private static void compare(Map<String, Object> changes, String key, Object before, Object after) {
        if (!Objects.equals(before, after)) {
            changes.put(key, Arrays.asList(before == null ? null : before.toString(),
                    after == null ? null : after.toString()));
        }
    }

    private ProjectView map(ResultSet rs, int rowNum) throws SQLException {
        return new ProjectView(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("name"),
                rs.getObject("customer_id", UUID.class),
                rs.getString("customer_name"),
                rs.getString("location"),
                rs.getString("country"),
                rs.getString("time_zone"),
                rs.getObject("start_date", LocalDate.class),
                rs.getObject("end_date", LocalDate.class),
                rs.getString("status"),
                json.readValue(rs.getString("shifts"), SHIFTS),
                rs.getInt("version"));
    }
}
