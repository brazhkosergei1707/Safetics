package com.safetics.shifts;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.safetics.alerts.AlertService;
import com.safetics.assets.AssetDtos.ChecklistItem;
import com.safetics.core.audit.AuditService;
import com.safetics.core.tenancy.TenantContext;
import com.safetics.core.web.ApiException;
import com.safetics.projects.ProjectDtos.ProjectView;
import com.safetics.projects.ProjectRoles;
import com.safetics.projects.ProjectService;
import com.safetics.shifts.ShiftDtos.WorkShiftView;
import com.safetics.tasks.TaskWorkflow;

import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Чек-лист зміни: пункти — з типу обладнання (asset_type.checklist). Подання закриває задачу
 * «Чек-лист зміни» (Пройдено / Не пройдено) і знімає блок зі змінного рапорту. Не пройдено →
 * задача «Сервіс» на супервайзера (критичність high, якщо не пройдено критичний пункт, інакше
 * medium) з пов'язаною задачею чек-листа; критичний пункт → ще й алерт менеджерам проєкту.
 * Нічого не блокує. Заповнюють бурильник зміни (виконавець задачі), супервайзер і менеджер.
 */
@Service
public class ShiftChecklistService {

    static final String CHECKLIST_TASK_TYPE = "shift_checklist";
    static final Set<String> RESULTS = Set.of("pass", "fail", "na");

    /** result: pass / fail / na; порожній — ще не заповнено. */
    public record ChecklistItemView(
            @NotNull String key,
            @NotNull Map<String, String> label,
            @NotNull boolean critical,
            @Schema(allowableValues = {"pass", "fail", "na"}) String result,
            String comment) {
    }

    /** status: new — ще не подано, passed / failed — подано. */
    public record ShiftChecklistView(
            @NotNull UUID shiftId,
            @NotNull UUID assetId,
            @NotNull String assetCode,
            @NotNull @Schema(allowableValues = {"new", "passed", "failed"}) String status,
            @NotNull List<ChecklistItemView> items,
            String comment,
            @NotNull boolean canEdit,
            UUID checklistTaskId,
            UUID serviceTaskId,
            Long serviceTaskNumber,
            OffsetDateTime submittedAt,
            String submittedByName) {
    }

    /** comment обов'язковий для fail. */
    public record ChecklistAnswer(
            @NotNull String key,
            @NotNull @Schema(allowableValues = {"pass", "fail", "na"}) String result,
            String comment) {
    }

    public record ChecklistSubmitRequest(
            @NotNull @Valid List<ChecklistAnswer> answers,
            String comment) {
    }

    private record Stored(String result, List<ChecklistItemView> items, String comment, UUID serviceTaskId,
                          Long serviceTaskNumber, OffsetDateTime submittedAt, String submittedByName) {
    }

    private static final TypeReference<List<ChecklistItem>> TEMPLATE = new TypeReference<>() {
    };
    private static final TypeReference<List<ChecklistItemView>> ITEMS = new TypeReference<>() {
    };

    private final JdbcClient db;
    private final JsonMapper json;
    private final ProjectService projects;
    private final ProjectRoles roles;
    private final WorkShiftService shifts;
    private final TaskWorkflow tasks;
    private final AlertService alerts;
    private final AuditService audit;

    public ShiftChecklistService(JdbcClient db, JsonMapper json, ProjectService projects, ProjectRoles roles,
                                 WorkShiftService shifts, TaskWorkflow tasks, AlertService alerts, AuditService audit) {
        this.db = db;
        this.json = json;
        this.projects = projects;
        this.roles = roles;
        this.shifts = shifts;
        this.tasks = tasks;
        this.alerts = alerts;
        this.audit = audit;
    }

    public ShiftChecklistView get(UUID shiftId) {
        WorkShiftView shift = shifts.get(shiftId);
        ProjectView project = projects.get(shift.projectId());
        Optional<Stored> stored = stored(shiftId, shift.assetId());
        UUID checklistTask = checklistTaskId(shiftId).orElse(null);
        List<ChecklistItemView> items = stored.map(Stored::items).orElseGet(() -> template(shift.assetId()).stream()
                .map(i -> new ChecklistItemView(i.key(), i.label(), Boolean.TRUE.equals(i.critical()), null, null)).toList());
        boolean canEdit = stored.isEmpty() && "active".equals(project.status()) && editor(project, checklistTask);
        return new ShiftChecklistView(shiftId, shift.assetId(), shift.assetCode(),
                stored.map(Stored::result).orElse("new"), items, stored.map(Stored::comment).orElse(null), canEdit,
                checklistTask, stored.map(Stored::serviceTaskId).orElse(null),
                stored.map(Stored::serviceTaskNumber).orElse(null), stored.map(Stored::submittedAt).orElse(null),
                stored.map(Stored::submittedByName).orElse(null));
    }

    @Transactional
    public ShiftChecklistView submit(UUID shiftId, ChecklistSubmitRequest request) {
        WorkShiftView shift = shifts.get(shiftId);
        ProjectView project = projects.get(shift.projectId());
        if ("closed".equals(project.status())) {
            throw ApiException.conflict("project_closed");
        }
        UUID checklistTask = checklistTaskId(shiftId).orElse(null);
        if (!editor(project, checklistTask)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", Map.of());
        }
        if (stored(shiftId, shift.assetId()).isPresent()) {
            throw ApiException.conflict("checklist_submitted");
        }
        Map<String, ChecklistAnswer> answers = new HashMap<>();
        for (ChecklistAnswer answer : request.answers()) {
            answers.put(answer.key(), answer);
        }
        Map<String, String> errors = new LinkedHashMap<>();
        List<ChecklistItemView> items = new ArrayList<>();
        List<ChecklistItemView> failed = new ArrayList<>();
        for (ChecklistItem item : template(shift.assetId())) {
            ChecklistAnswer answer = answers.get(item.key());
            if (answer == null || !RESULTS.contains(answer.result())) {
                errors.put("items." + item.key(), "required");
                continue;
            }
            String comment = answer.comment() == null || answer.comment().isBlank() ? null : answer.comment().trim();
            if ("fail".equals(answer.result()) && comment == null) {
                errors.put("items." + item.key(), "comment_required");
            }
            ChecklistItemView view = new ChecklistItemView(item.key(), item.label(), Boolean.TRUE.equals(item.critical()),
                    answer.result(), comment);
            items.add(view);
            if ("fail".equals(answer.result())) {
                failed.add(view);
            }
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        String result = failed.isEmpty() ? "passed" : "failed";
        boolean critical = failed.stream().anyMatch(ChecklistItemView::critical);
        UUID id = db.sql("""
                        insert into shift_checklist (company_id, shift_id, asset_id, result, items, comment, submitted_by)
                        values (?, ?, ?, ?, cast(? as jsonb), ?, ?)
                        returning id
                        """)
                .params(TenantContext.requireCompanyId(), shiftId, shift.assetId(), result, json.writeValueAsString(items),
                        request.comment() == null || request.comment().isBlank() ? null : request.comment().trim(),
                        currentUserId())
                .query(UUID.class).single();
        if (checklistTask != null) {
            tasks.moveToStatus(checklistTask, result, "done", false);
        }
        if (!failed.isEmpty()) {
            String labels = String.join(", ", failed.stream().map(f -> TaskWorkflow.localizedName(f.label())).toList());
            String title = shift.assetCode() + ": " + labels;
            StringBuilder description = new StringBuilder();
            for (ChecklistItemView f : failed) {
                description.append(f.critical() ? "‼ " : "• ").append(TaskWorkflow.localizedName(f.label()));
                if (f.comment() != null) {
                    description.append(" — ").append(f.comment());
                }
                description.append('\n');
            }
            Optional<UUID> service = tasks.createLinkedTask(project, "service", shift.assetId(), title,
                    description.toString().trim(), Map.of("criticality", critical ? "high" : "medium"), checklistTask);
            if (service.isPresent()) {
                db.sql("update shift_checklist set service_task_id = ? where id = ?").params(service.get(), id).update();
                if (critical) {
                    alerts.notifyManagers(project.id(), "critical_checklist", title, service.get());
                }
            }
        }
        audit.record("shift_checklist", id, "submit", Map.of("shiftId", shiftId, "result", result, "failed", failed.size()));
        return get(shiftId);
    }

    private List<ChecklistItem> template(UUID assetId) {
        return db.sql("select t.checklist from asset a join asset_type t on t.id = a.asset_type_id where a.id = ?")
                .param(assetId).query(String.class).optional().map(v -> json.readValue(v, TEMPLATE)).orElse(List.of());
    }

    private Optional<Stored> stored(UUID shiftId, UUID assetId) {
        return db.sql("""
                        select c.result, c.items, c.comment, c.service_task_id, t.number, c.submitted_at, u.full_name
                        from shift_checklist c
                                 left join task t on t.id = c.service_task_id
                                 left join app_user u on u.id = c.submitted_by
                        where c.shift_id = ? and c.asset_id = ?
                        """)
                .params(shiftId, assetId)
                .query((rs, n) -> new Stored(rs.getString("result"), json.readValue(rs.getString("items"), ITEMS),
                        rs.getString("comment"), rs.getObject("service_task_id", UUID.class),
                        rs.getObject("number") == null ? null : rs.getLong("number"),
                        rs.getObject("submitted_at", OffsetDateTime.class), rs.getString("full_name")))
                .optional();
    }

    private Optional<UUID> checklistTaskId(UUID shiftId) {
        return db.sql("""
                        select t.id from task t join task_type tt on tt.id = t.task_type_id
                        where t.work_shift_id = ? and tt.code = ?
                        order by t.number limit 1
                        """)
                .params(shiftId, CHECKLIST_TASK_TYPE).query(UUID.class).optional();
    }

    private boolean editor(ProjectView project, UUID checklistTask) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null) {
            for (GrantedAuthority authority : authentication.getAuthorities()) {
                if ("project.manage".equals(authority.getAuthority())) {
                    return true;
                }
            }
        }
        if (roles.isSupervisor(project)) {
            return true;
        }
        UUID user = currentUserId();
        return checklistTask != null && user != null
                && tasks.find(checklistTask).map(t -> user.equals(t.assigneeId())).orElse(false);
    }

    private static UUID currentUserId() {
        return TenantContext.current().map(TenantContext.Tenant::userId).orElse(null);
    }
}
