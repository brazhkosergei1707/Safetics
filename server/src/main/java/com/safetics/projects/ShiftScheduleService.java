package com.safetics.projects;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.safetics.core.audit.AuditService;
import com.safetics.core.tenancy.TenantContext;
import com.safetics.core.web.ApiException;
import com.safetics.projects.ProjectDtos.ProjectView;
import com.safetics.projects.ProjectDtos.Shift;
import com.safetics.projects.ProjectDtos.ShiftScheduleRequest;
import com.safetics.projects.ProjectDtos.ShiftScheduleView;

import tools.jackson.databind.json.JsonMapper;

/**
 * Версії графіка змін проєкту. Минулі й поточна версії не змінюються (за ними вже є рапорти),
 * нова версія — лише з майбутньої дати. Майбутню версію можна замінити або видалити.
 */
@Service
public class ShiftScheduleService {

    private record Row(UUID id, LocalDate validFrom, String shifts) {
    }

    private final JdbcClient db;
    private final JsonMapper json;
    private final ProjectService projects;
    private final AuditService audit;

    public ShiftScheduleService(JdbcClient db, JsonMapper json, ProjectService projects, AuditService audit) {
        this.db = db;
        this.json = json;
        this.projects = projects;
        this.audit = audit;
    }

    public List<ShiftScheduleView> list(UUID projectId) {
        ProjectView project = projects.get(projectId);
        LocalDate today = projects.today(project);
        List<Row> rows = rows(projectId);
        UUID current = currentId(rows, today);
        return rows.stream()
                .map(r -> new ShiftScheduleView(r.id(), r.validFrom(), json.readValue(r.shifts(), ProjectService.SHIFTS),
                        r.validFrom().isAfter(today), r.id().equals(current)))
                .toList();
    }

    @Transactional
    public List<ShiftScheduleView> save(UUID projectId, ShiftScheduleRequest request) {
        ProjectView project = projects.get(projectId);
        if ("closed".equals(project.status())) {
            throw ApiException.conflict("project_closed");
        }
        LocalDate today = projects.today(project);
        if (!request.validFrom().isAfter(today)) {
            throw ApiException.validation("validFrom", "not_in_future");
        }
        if (request.validFrom().isBefore(project.startDate())) {
            throw ApiException.validation("validFrom", "before_project_start");
        }
        List<Shift> shifts = ShiftRules.validate(request.shifts());
        String shiftsJson = json.writeValueAsString(shifts);

        int replaced = db.sql("update shift_schedule set shifts = cast(? as jsonb) where project_id = ? and valid_from = ?")
                .params(shiftsJson, projectId, request.validFrom()).update();
        if (replaced == 0) {
            db.sql("""
                            insert into shift_schedule (company_id, project_id, valid_from, shifts, created_by)
                            values (?, ?, ?, cast(? as jsonb), ?)
                            """)
                    .params(TenantContext.requireCompanyId(), projectId, request.validFrom(), shiftsJson,
                            TenantContext.current().map(TenantContext.Tenant::userId).orElse(null))
                    .update();
        }
        audit.record("project", projectId, replaced == 0 ? "add_shift_schedule" : "replace_shift_schedule",
                Map.of("validFrom", request.validFrom().toString(), "shifts", shifts));
        return list(projectId);
    }

    @Transactional
    public List<ShiftScheduleView> delete(UUID projectId, UUID scheduleId) {
        ProjectView project = projects.get(projectId);
        List<Row> rows = rows(projectId);
        Row row = rows.stream().filter(r -> r.id().equals(scheduleId)).findFirst().orElseThrow(ApiException::notFound);
        if (!row.validFrom().isAfter(projects.today(project))) {
            throw ApiException.conflict("schedule_in_effect");
        }
        if (rows.size() == 1) {
            throw ApiException.conflict("last_schedule");
        }
        db.sql("delete from shift_schedule where id = ?").param(scheduleId).update();
        audit.record("project", projectId, "delete_shift_schedule", Map.of("validFrom", row.validFrom().toString()));
        return list(projectId);
    }

    private List<Row> rows(UUID projectId) {
        return db.sql("select id, valid_from, shifts from shift_schedule where project_id = ? order by valid_from")
                .param(projectId)
                .query((rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getObject("valid_from", LocalDate.class),
                        rs.getString("shifts")))
                .list();
    }

    /** Остання версія з датою ≤ сьогодні, інакше перша. */
    private static UUID currentId(List<Row> rows, LocalDate today) {
        UUID current = rows.isEmpty() ? null : rows.getFirst().id();
        for (Row row : rows) {
            if (!row.validFrom().isAfter(today)) {
                current = row.id();
            }
        }
        return current;
    }
}
