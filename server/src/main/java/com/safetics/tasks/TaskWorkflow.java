package com.safetics.tasks;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

import com.safetics.assets.AttributeValidator;
import com.safetics.assets.FieldDefinition;
import com.safetics.core.audit.AuditService;
import com.safetics.core.tenancy.TenantContext;
import com.safetics.core.web.ApiException;
import com.safetics.projects.ProjectDtos.ProjectView;
import com.safetics.projects.ProjectRoles;
import com.safetics.projects.ProjectService;
import com.safetics.tasks.TaskDtos.TaskCreateRequest;
import com.safetics.tasks.TaskDtos.TaskHistoryEntry;
import com.safetics.tasks.TaskDtos.TaskLinksView;
import com.safetics.tasks.TaskDtos.TaskRef;
import com.safetics.tasks.TaskDtos.TaskRule;
import com.safetics.tasks.TaskDtos.TaskStatusDefinition;
import com.safetics.tasks.TaskDtos.TaskStatusRequest;
import com.safetics.tasks.TaskDtos.TaskTypeView;
import com.safetics.tasks.TaskDtos.TaskUpdateRequest;
import com.safetics.tasks.TaskDtos.TaskView;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Задачі: створення, зміни, статуси за категоріями, зв'язки, історія.
 *
 * <p>Правила переходів (за категорією статусу):
 * <ul>
 *   <li>в active — немає незавершених задач, що блокують цю; записується фактичний початок;</li>
 *   <li>в done — те саме + заповнена форма закриття + (якщо тип вимагає) закриті підзадачі;
 *       записується фактичний кінець;</li>
 *   <li>з done / cancelled назад — лише менеджер або супервайзер проєкту.</li>
 * </ul>
 * Якщо тип синхронізує послугу: задача в роботі → послуга «в роботі», завершена → «завершено», а
 * значення форми закриття з ключами полів послуги (actualDepth) записуються в послугу. Головна задача
 * послуги (service.main_task_id) ще й скасовує / повертає послугу та переносить у неї планові дати.
 *
 * <p>Права: менеджер (project.manage) і супервайзер проєкту — усе; виконавець — статус, дати
 * (план / прогноз) і форма закриття своїх задач; решта з task.view — перегляд.
 */
@Service
public class TaskWorkflow {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };
    private static final Set<String> CLOSED = Set.of("done", "cancelled");

    private static final String SELECT = """
            select t.id, t.number, t.project_id, p.code as project_code, t.task_type_id, tt.code as type_code, t.title,
                   t.description, t.status, t.status_category, t.assignee_id, au.full_name as assignee_name,
                   t.author_id, ou.full_name as author_name, t.service_id, s.code as service_code, t.asset_id, a.code as asset_code,
                   t.parent_id, pt.number as parent_number, t.work_shift_id, t.planned_start, t.planned_end, t.forecast_end,
                   t.actual_start, t.actual_end, t.attributes, t.closing_values, t.version, t.created_at,
                   (select count(*) from task_link l join task b on b.id = l.from_task
                    where l.to_task = t.id and l.kind = 'blocks' and b.status_category not in ('done', 'cancelled')) as open_blockers,
                   (select count(*) from task c where c.parent_id = t.id) as children_total,
                   (select count(*) from task c where c.parent_id = t.id
                    and c.status_category not in ('done', 'cancelled')) as children_open
            from task t
                     join project p on p.id = t.project_id
                     join task_type tt on tt.id = t.task_type_id
                     left join app_user au on au.id = t.assignee_id
                     left join app_user ou on ou.id = t.author_id
                     left join service s on s.id = t.service_id
                     left join asset a on a.id = t.asset_id
                     left join task pt on pt.id = t.parent_id
            """;

    private record Actor(boolean manage, boolean supervisor, UUID userId) {
        boolean full() {
            return manage || supervisor;
        }
    }

    public record Filter(String category, UUID assigneeId, UUID typeId, UUID serviceId) {
    }

    private final JdbcClient db;
    private final JsonMapper json;
    private final ProjectService projects;
    private final ProjectRoles roles;
    private final TaskTypeService types;
    private final AuditService audit;

    public TaskWorkflow(JdbcClient db, JsonMapper json, ProjectService projects, ProjectRoles roles,
                        TaskTypeService types, AuditService audit) {
        this.db = db;
        this.json = json;
        this.projects = projects;
        this.roles = roles;
        this.types = types;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- читання

    public List<TaskView> list(UUID projectId, Filter filter) {
        projects.get(projectId);
        StringBuilder where = new StringBuilder(" where t.project_id = ?");
        List<Object> params = new ArrayList<>(List.of(projectId));
        appendFilter(filter, where, params);
        return db.sql(SELECT + where + " order by t.number").params(params).query(this::map).list();
    }

    /** Задачі поточного користувача з усіх видимих проєктів; за замовчуванням — відкриті. */
    public List<TaskView> mine(boolean includeClosed) {
        String where = " where t.assignee_id = app_user_id()"
                + (includeClosed ? "" : " and t.status_category in ('new', 'active')");
        return db.sql(SELECT + where + " order by t.planned_end nulls last, t.number").query(this::map).list();
    }

    public TaskView get(UUID id) {
        return find(id).orElseThrow(ApiException::notFound);
    }

    public Optional<TaskView> find(UUID id) {
        return db.sql(SELECT + " where t.id = ?").param(id).query(this::map).optional();
    }

    public TaskLinksView links(UUID id) {
        get(id);
        List<TaskRef> blocks = refs("""
                select l.id as link_id, t.id, t.number, t.title, t.status, t.status_category
                from task_link l join task t on t.id = l.to_task
                where l.from_task = ? and l.kind = 'blocks' order by t.number
                """, id);
        List<TaskRef> blockedBy = refs("""
                select l.id as link_id, t.id, t.number, t.title, t.status, t.status_category
                from task_link l join task t on t.id = l.from_task
                where l.to_task = ? and l.kind = 'blocks' order by t.number
                """, id);
        List<TaskRef> related = db.sql("""
                        select l.id as link_id, t.id, t.number, t.title, t.status, t.status_category
                        from task_link l join task t on t.id = case when l.from_task = :id then l.to_task else l.from_task end
                        where (l.from_task = :id or l.to_task = :id) and l.kind = 'relates' order by t.number
                        """)
                .param("id", id).query(TaskWorkflow::ref).list();
        List<TaskRef> children = refs("""
                select null::uuid as link_id, t.id, t.number, t.title, t.status, t.status_category
                from task t where t.parent_id = ? order by t.number
                """, id);
        return new TaskLinksView(blocks, blockedBy, related, children);
    }

    public List<TaskHistoryEntry> history(UUID id) {
        get(id);
        return db.sql("""
                        select h.id, u.full_name, h.action, h.changes, h.created_at
                        from task_history h left join app_user u on u.id = h.user_id
                        where h.task_id = ? order by h.created_at, h.id
                        """)
                .param(id)
                .query((rs, n) -> new TaskHistoryEntry(rs.getLong("id"), rs.getString("full_name"), rs.getString("action"),
                        rs.getString("changes") == null ? null : json.readValue(rs.getString("changes"), MAP),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .list();
    }

    // ---------------------------------------------------------------- зміни

    @Transactional
    public TaskView create(UUID projectId, TaskCreateRequest request) {
        ProjectView project = openProject(projectId);
        Actor actor = actor(project);
        if (!actor.full()) {
            throw forbidden();
        }
        TaskTypeView type = types.find(request.typeId()).orElseThrow(() -> ApiException.validation("typeId", "not_found"));
        if (type.archived()) {
            throw ApiException.validation("typeId", "archived");
        }
        checkSubject(type, project, request.serviceId(), request.assetId());
        if (request.parentId() != null) {
            TaskView parent = find(request.parentId()).orElseThrow(() -> ApiException.validation("parentId", "not_found"));
            if (!parent.projectId().equals(projectId)) {
                throw ApiException.validation("parentId", "other_project");
            }
            if (CLOSED.contains(parent.statusCategory())) {
                throw ApiException.validation("parentId", "closed");
            }
        }
        checkAssignee(project, request.assigneeId());
        checkDates(request.plannedStart(), request.plannedEnd());
        Map<String, Object> attributes = validAttributes(type.fields(), request.attributes(), "attributes");
        TaskStatusDefinition initial = type.statuses().getFirst();

        UUID id = insertTask(projectId, type, initial, request.title().trim(), blankToNull(request.description()),
                request.assigneeId(), actor.userId(), request.serviceId(), request.assetId(), request.parentId(),
                request.plannedStart(), request.plannedEnd(), attributes);
        history(id, "create", Map.of("type", type.code(), "title", request.title().trim(), "status", initial.code()));
        audit.record("task", id, "create", Map.of("projectId", projectId));
        runRules(get(id), type, project, "created", null, 0);
        return get(id);
    }

    @Transactional
    public TaskView update(UUID id, TaskUpdateRequest request) {
        TaskView existing = get(id);
        ProjectView project = openProject(existing.projectId());
        Actor actor = actor(project);
        boolean assignee = actor.userId() != null && actor.userId().equals(existing.assigneeId());
        if (!actor.full() && !assignee) {
            throw forbidden();
        }
        if (CLOSED.contains(existing.statusCategory())) {
            throw ApiException.conflict("task_closed");
        }
        if (request.version() != existing.version()) {
            throw ApiException.conflict("stale_version");
        }
        TaskTypeView type = types.get(existing.typeId());
        Map<String, Object> attributes = request.attributes() == null
                ? new LinkedHashMap<>(existing.attributes())
                : validAttributes(type.fields(), request.attributes(), "attributes");
        UUID serviceId = "service".equals(type.subjectKind()) ? request.serviceId() : null;
        UUID assetId = "asset".equals(type.subjectKind()) ? request.assetId() : null;

        if (!actor.full()) {
            // Виконавець змінює лише дати своєї задачі
            boolean other = !Objects.equals(request.title().trim(), existing.title())
                    || !Objects.equals(blankToNull(request.description()), existing.description())
                    || !Objects.equals(request.assigneeId(), existing.assigneeId())
                    || !Objects.equals(serviceId, existing.serviceId())
                    || !Objects.equals(assetId, existing.assetId())
                    || !sameMaps(attributes, existing.attributes());
            if (other) {
                throw new ApiException(HttpStatus.FORBIDDEN, "assignee_dates_only", Map.of());
            }
        }
        if (!Objects.equals(serviceId, existing.serviceId()) || !Objects.equals(assetId, existing.assetId())) {
            checkSubject(type, project, serviceId, assetId);
        }
        if (!Objects.equals(request.assigneeId(), existing.assigneeId())) {
            checkAssignee(project, request.assigneeId());
        }
        checkDates(request.plannedStart(), request.plannedEnd());

        int updated = db.sql("""
                        update task
                        set title = ?, description = ?, assignee_id = ?, service_id = ?, asset_id = ?, planned_start = ?,
                            planned_end = ?, forecast_end = ?, attributes = cast(? as jsonb), version = version + 1,
                            updated_at = now()
                        where id = ? and version = ?
                        """)
                .params(request.title().trim(), blankToNull(request.description()), request.assigneeId(), serviceId, assetId,
                        request.plannedStart(), request.plannedEnd(), request.forecastEnd(), json.writeValueAsString(attributes),
                        id, request.version())
                .update();
        if (updated == 0) {
            throw ApiException.conflict("stale_version");
        }
        TaskView saved = get(id);
        Map<String, Object> changes = changes(existing, saved);
        if (!changes.isEmpty()) {
            history(id, "update", changes);
        }
        // Головна задача: планові дати — і в послузі
        if (saved.serviceId() != null && (!Objects.equals(existing.plannedStart(), saved.plannedStart())
                || !Objects.equals(existing.plannedEnd(), saved.plannedEnd()))) {
            int synced = db.sql("""
                            update service set planned_start = ?, planned_end = ?, version = version + 1, updated_at = now()
                            where id = ? and main_task_id = ? and status in ('planned', 'in_progress')
                            """)
                    .params(saved.plannedStart(), saved.plannedEnd(), saved.serviceId(), id).update();
            if (synced > 0) {
                audit.record("service", saved.serviceId(), "update", Map.of("byTask", saved.number(),
                        "plannedStart", String.valueOf(saved.plannedStart()), "plannedEnd", String.valueOf(saved.plannedEnd())));
            }
        }
        return saved;
    }

    @Transactional
    public TaskView changeStatus(UUID id, TaskStatusRequest request) {
        TaskView existing = get(id);
        ProjectView project = openProject(existing.projectId());
        Actor actor = actor(project);
        boolean assignee = actor.userId() != null && actor.userId().equals(existing.assigneeId());
        if (!actor.full() && !assignee) {
            throw forbidden();
        }
        if (request.version() != existing.version()) {
            throw ApiException.conflict("stale_version");
        }
        TaskTypeView type = types.get(existing.typeId());
        TaskStatusDefinition target = type.statuses().stream().filter(s -> s.code().equals(request.status())).findFirst()
                .orElseThrow(() -> ApiException.validation("status", "invalid_option"));
        if (target.code().equals(existing.status())) {
            return existing;
        }
        String from = existing.statusCategory();
        String to = target.category();
        if (CLOSED.contains(from) && !CLOSED.contains(to) && !actor.full()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "reopen_not_allowed", Map.of());
        }
        if ("cancelled".equals(to) && !actor.full()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "cancel_not_allowed", Map.of());
        }
        applyStatus(existing, type, project, target, request.closingValues());
        return get(id);
    }

    /**
     * Перехід у статус без перевірки прав (їх перевіряє той, хто викликає): блокери, підзадачі, форма
     * закриття, фактичні дати, історія, синхронізація з послугою, правила автоматизації.
     */
    private void applyStatus(TaskView existing, TaskTypeView type, ProjectView project, TaskStatusDefinition target,
                             Map<String, Object> closingValues) {
        UUID id = existing.id();
        String from = existing.statusCategory();
        String to = target.category();
        if (("active".equals(to) || "done".equals(to)) && existing.openBlockers() > 0) {
            throw ApiException.conflict("blocked");
        }
        Map<String, Object> closing = null;
        if ("done".equals(to)) {
            if (type.childrenBlockClose() && existing.childrenOpen() > 0) {
                throw ApiException.conflict("open_children");
            }
            closing = validAttributes(type.closingFields(), closingValues, "closingValues");
        }

        String actualStart = "active".equals(to) || "done".equals(to) ? "coalesce(actual_start, now())" : "actual_start";
        String actualEnd = "done".equals(to) || "cancelled".equals(to) ? "now()" : "null";
        db.sql("update task set status = ?, status_category = ?, actual_start = " + actualStart + ", actual_end = " + actualEnd
                        + ", closing_values = " + (closing != null ? "cast(? as jsonb)" : CLOSED.contains(to) ? "closing_values" : "null")
                        + ", version = version + 1, updated_at = now() where id = ?")
                .params(closing != null
                        ? List.of(target.code(), to, json.writeValueAsString(closing), id)
                        : List.of(target.code(), to, id))
                .update();

        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("status", Arrays.asList(existing.status(), target.code()));
        if (closing != null && !closing.isEmpty()) {
            changes.put("closingValues", closing);
        }
        history(id, "status", changes);

        if (type.syncService() && existing.serviceId() != null) {
            syncService(existing, to, closing);
        }
        if (!from.equals(to)) {
            runRules(get(id), type, project, "status_category", to, 0);
        }
    }

    // ---------------------------------------------------------------- головна задача послуги

    /**
     * Головна задача нової послуги: тип — з типу послуги, ті самі дати, виконавець — заданий або
     * супервайзер проєкту; назва «тип: код послуги». Від імені системи: права на послугу вже
     * перевірено, а замовник (портал) задач не бачить. Правила типу (підзадачі) виконуються.
     */
    @Transactional
    public UUID createMainTask(ProjectView project, UUID taskTypeId, UUID serviceId, String serviceCode, UUID assigneeId,
                               LocalDate plannedStart, LocalDate plannedEnd) {
        return asSystem(() -> {
            TaskTypeView type = types.get(taskTypeId);
            if (assigneeId != null) {
                checkAssignee(project, assigneeId);
            }
            UUID assignee = assigneeId != null ? assigneeId : roleHolder("supervisor", project);
            String title = localizedName(type.name()) + ": " + serviceCode;
            TaskStatusDefinition initial = type.statuses().getFirst();
            UUID id = insertTask(project.id(), type, initial, title.length() > 300 ? title.substring(0, 300) : title, null,
                    assignee, currentUserId(), serviceId, null, null, plannedStart, plannedEnd, new LinkedHashMap<>());
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("type", type.code());
            details.put("title", title);
            details.put("status", initial.code());
            details.put("byService", serviceCode);
            history(id, "create", details);
            audit.record("task", id, "create", Map.of("projectId", project.id(), "serviceId", serviceId));
            runRules(get(id), type, project, "created", null, 0);
            return id;
        });
    }

    /**
     * Дія з послугою → статус головної задачі: перший статус потрібної категорії її типу. Нічого не
     * робить, якщо задача вже в цій категорії або тип такого статусу не має. Права перевіряє послуга.
     */
    @Transactional
    public void moveMainTask(UUID taskId, String category, Map<String, Object> closingValues) {
        asSystem(() -> {
            TaskView existing = get(taskId);
            if (category.equals(existing.statusCategory())) {
                return null;
            }
            TaskTypeView type = types.get(existing.typeId());
            Optional<TaskStatusDefinition> target = type.statuses().stream()
                    .filter(s -> category.equals(s.category())).findFirst();
            if (target.isEmpty()) {
                history(taskId, "automation_skipped", Map.of("reason", "no_status", "category", category));
                return null;
            }
            applyStatus(existing, type, projects.get(existing.projectId()), target.get(), closingValues);
            return null;
        });
    }

    /** Зміна послуги → дати й виконавець головної задачі, поки вона відкрита. */
    @Transactional
    public void syncMainTask(UUID taskId, LocalDate plannedStart, LocalDate plannedEnd, boolean changeAssignee,
                             UUID assigneeId) {
        asSystem(() -> {
            TaskView existing = get(taskId);
            if (CLOSED.contains(existing.statusCategory())) {
                return null;
            }
            UUID assignee = changeAssignee ? assigneeId : existing.assigneeId();
            if (Objects.equals(plannedStart, existing.plannedStart()) && Objects.equals(plannedEnd, existing.plannedEnd())
                    && Objects.equals(assignee, existing.assigneeId())) {
                return null;
            }
            if (!Objects.equals(assignee, existing.assigneeId())) {
                checkAssignee(projects.get(existing.projectId()), assignee);
            }
            db.sql("""
                            update task set planned_start = ?, planned_end = ?, assignee_id = ?, version = version + 1,
                                            updated_at = now()
                            where id = ?
                            """)
                    .params(plannedStart, plannedEnd, assignee, taskId).update();
            Map<String, Object> changes = changes(existing, get(taskId));
            if (!changes.isEmpty()) {
                changes.put("byService", existing.serviceCode());
                history(taskId, "update", changes);
            }
            return null;
        });
    }

    // ---------------------------------------------------------------- задачі обладнання й зміни

    /**
     * Задача про обладнання на проєкті («Бурова на проєкті»): від імені системи, одразу в першому
     * статусі категорії «в роботі» (якщо є), виконавець — супервайзер проєкту.
     */
    @Transactional
    public UUID createAssetTask(ProjectView project, TaskTypeView type, UUID assetId, String assetCode) {
        return asSystem(() -> {
            TaskStatusDefinition status = type.statuses().stream().filter(s -> "active".equals(s.category())).findFirst()
                    .orElse(type.statuses().getFirst());
            String title = localizedName(type.name()) + ": " + assetCode;
            UUID id = insertTask(project.id(), type, status, title, null, roleHolder("supervisor", project), currentUserId(),
                    null, assetId, null, projects.today(project), null, new LinkedHashMap<>());
            if ("active".equals(status.category())) {
                db.sql("update task set actual_start = now() where id = ?").param(id).update();
            }
            history(id, "create", Map.of("type", type.code(), "title", title, "status", status.code(), "byAssignment", assetCode));
            runRules(get(id), type, project, "created", null, 0);
            return id;
        });
    }

    /** Нова зміна → правила shift_created задачі обладнання (змінний рапорт, далі — чек-лист). */
    @Transactional
    public void runShiftCreated(UUID taskId, ShiftContext shift) {
        asSystem(() -> {
            TaskView task = find(taskId).orElse(null);
            if (task == null || CLOSED.contains(task.statusCategory())) {
                return null;
            }
            runRules(task, types.get(task.typeId()), projects.get(task.projectId()), "shift_created", null, 0, shift);
            return null;
        });
    }

    /** Змінився бурильник зміни: відкриті задачі зміни, що були на попередньому (або без виконавця), — на нового. */
    @Transactional
    public void reassignShiftTasks(UUID shiftId, UUID previousLead, UUID newLead) {
        if (newLead == null || newLead.equals(previousLead)) {
            return;
        }
        asSystem(() -> {
            List<UUID> ids = db.sql("""
                            select id from task
                            where work_shift_id = ? and status_category in ('new', 'active')
                              and (assignee_id is null or assignee_id = ?)
                            """)
                    .params(shiftId, previousLead).query(UUID.class).list();
            for (UUID id : ids) {
                TaskView before = get(id);
                db.sql("update task set assignee_id = ?, version = version + 1, updated_at = now() where id = ?")
                        .params(newLead, id).update();
                history(id, "update", Map.of("assignee", Arrays.asList(before.assigneeName(), get(id).assigneeName()),
                        "byShift", true));
            }
            return null;
        });
    }

    /**
     * Дії системи (app.system у межах транзакції): задачі видно й без task.view. Попереднє значення
     * відновлюється, тож вкладені виклики безпечні.
     */
    private <T> T asSystem(java.util.function.Supplier<T> action) {
        String previous = db.sql("select coalesce(current_setting('app.system', true), '')").query(String.class).single();
        db.sql("select set_config('app.system', 'on', true)").query(String.class).single();
        try {
            return action.get();
        } finally {
            db.sql("select set_config('app.system', ?, true)").param(previous).query(String.class).single();
        }
    }

    /** kind: blocks — ця задача блокує taskId; blocked_by — taskId блокує цю; relates. */
    @Transactional
    public TaskLinksView addLink(UUID id, UUID otherId, String kind) {
        TaskView task = get(id);
        ProjectView project = openProject(task.projectId());
        if (!actor(project).full()) {
            throw forbidden();
        }
        TaskView other = find(otherId).orElseThrow(() -> ApiException.validation("taskId", "not_found"));
        if (!other.projectId().equals(task.projectId())) {
            throw ApiException.validation("taskId", "other_project");
        }
        if (other.id().equals(id)) {
            throw ApiException.validation("taskId", "self");
        }
        UUID from;
        UUID to;
        String stored;
        switch (kind) {
            case "blocks" -> {
                from = id;
                to = otherId;
                stored = "blocks";
            }
            case "blocked_by" -> {
                from = otherId;
                to = id;
                stored = "blocks";
            }
            case "relates" -> {
                from = id;
                to = otherId;
                stored = "relates";
            }
            default -> throw ApiException.validation("kind", "invalid_option");
        }
        if ("blocks".equals(stored) && reaches(to, from)) {
            throw ApiException.validation("taskId", "cycle");
        }
        int added = db.sql("""
                        insert into task_link (company_id, from_task, to_task, kind, created_by)
                        values (?, ?, ?, ?, ?)
                        on conflict do nothing
                        """)
                .params(TenantContext.requireCompanyId(), from, to, stored, currentUserId()).update();
        if (added > 0) {
            Map<String, Object> details = Map.of("kind", kind, "task", other.number());
            history(id, "link", details);
        }
        return links(id);
    }

    @Transactional
    public TaskLinksView removeLink(UUID id, UUID linkId) {
        TaskView task = get(id);
        ProjectView project = openProject(task.projectId());
        if (!actor(project).full()) {
            throw forbidden();
        }
        int removed = db.sql("delete from task_link where id = ? and (from_task = ? or to_task = ?)")
                .params(linkId, id, id).update();
        if (removed > 0) {
            history(id, "unlink", Map.of("linkId", linkId));
        }
        return links(id);
    }

    // ---------------------------------------------------------------- автоматизація

    /** Глибина вкладених правил (підзадача теж може мати правила): далі не йдемо, щоб не зациклитися. */
    static final int MAX_RULE_DEPTH = 3;

    /**
     * Виконує правила типу для тригера: створює підзадачі, призначає їх за роллю на проєкті,
     * ставить строк і зв'язки «блокує». Працює від імені системи (права людини вже перевірено).
     */
    /** Зміна, у контексті якої виконуються правила (shift_created і вкладені): підзадачі — задачі зміни. */
    public record ShiftContext(UUID shiftId, UUID leadUserId, String label) {
    }

    private void runRules(TaskView task, TaskTypeView type, ProjectView project, String trigger, String category, int depth) {
        runRules(task, type, project, trigger, category, depth, null);
    }

    private void runRules(TaskView task, TaskTypeView type, ProjectView project, String trigger, String category, int depth,
                          ShiftContext shift) {
        List<TaskRule> rules = type.rules();
        if (rules.isEmpty() || depth >= MAX_RULE_DEPTH) {
            return;
        }
        Map<Integer, UUID> created = new LinkedHashMap<>();
        List<Long> numbers = new ArrayList<>();
        for (int i = 0; i < rules.size(); i++) {
            TaskRule rule = rules.get(i);
            if (!trigger.equals(rule.trigger()) || ("status_category".equals(trigger) && !Objects.equals(category, rule.category()))) {
                continue;
            }
            Optional<TaskTypeView> childType = types.findByCode(rule.taskType()).filter(t -> !t.archived());
            if (childType.isEmpty()) {
                history(task.id(), "automation_skipped", Map.of("rule", i, "reason", "type_not_found", "taskType", rule.taskType()));
                continue;
            }
            TaskTypeView ct = childType.get();
            UUID serviceId = "service".equals(ct.subjectKind()) ? task.serviceId() : null;
            UUID assetId = "asset".equals(ct.subjectKind()) ? task.assetId() : null;
            if (("service".equals(ct.subjectKind()) && serviceId == null) || ("asset".equals(ct.subjectKind()) && assetId == null)) {
                history(task.id(), "automation_skipped", Map.of("rule", i, "reason", "no_subject", "taskType", rule.taskType()));
                continue;
            }
            UUID assignee = "shift_lead".equals(rule.assignTo())
                    ? (shift != null && shift.leadUserId() != null ? shift.leadUserId() : roleHolder("supervisor", project))
                    : assigneeFor(rule.assignTo(), task, project);
            LocalDate due = rule.dueInDays() == null ? null : projects.today(project).plusDays(rule.dueInDays());
            String title = title(rule.title(), ct, task, shift == null ? null : shift.label());
            UUID childId = insertTask(project.id(), ct, ct.statuses().getFirst(), title, null, assignee, task.authorId(),
                    serviceId, assetId, task.id(), null, due, new LinkedHashMap<>());
            UUID shiftId = shift != null ? shift.shiftId() : task.workShiftId();
            if (shiftId != null) {
                db.sql("update task set work_shift_id = ? where id = ?").params(shiftId, childId).update();
            }
            created.put(i, childId);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("type", ct.code());
            details.put("title", title);
            details.put("byRule", i);
            details.put("parent", task.number());
            if (assignee == null && rule.assignTo() != null && !"none".equals(rule.assignTo())) {
                details.put("assigneeNotFound", rule.assignTo());
            }
            history(childId, "auto_create", details);
            if (rule.blockedBy() != null && created.containsKey(rule.blockedBy())) {
                db.sql("insert into task_link (company_id, from_task, to_task, kind) values (?, ?, ?, 'blocks') on conflict do nothing")
                        .params(TenantContext.requireCompanyId(), created.get(rule.blockedBy()), childId).update();
            }
            TaskView child = get(childId);
            numbers.add(child.number());
            runRules(child, ct, project, "created", null, depth + 1, shift);
        }
        if (!numbers.isEmpty()) {
            history(task.id(), "automation", Map.of("created", numbers));
        }
    }

    /** Виконавець підзадачі за роллю: людина з входом, яка сьогодні на проєкті на посаді з цією роллю. */
    private UUID assigneeFor(String assignTo, TaskView task, ProjectView project) {
        if (assignTo == null) {
            return null;
        }
        return switch (assignTo) {
            case "parent_assignee" -> task.assigneeId();
            case "author" -> task.authorId();
            case "supervisor", "storekeeper" -> roleHolder(assignTo, project);
            default -> null;
        };
    }

    /** Людина з входом, яка сьогодні на проєкті на посаді з цією роллю (supervisor, storekeeper). */
    private UUID roleHolder(String role, ProjectView project) {
        LocalDate today = projects.today(project);
        return db.sql("""
                        select p.user_id
                        from personnel_assignment a
                                 join personnel p on p.id = a.personnel_id
                                 join position pos on pos.id = a.position_id
                                 join membership m on m.company_id = p.company_id and m.user_id = p.user_id
                        where a.project_id = ?
                          and pos.project_role = ?
                          and p.user_id is not null
                          and m.status = 'active'
                          and a.start_date <= ?
                          and (a.end_date is null or a.end_date >= ?)
                        order by a.start_date
                        limit 1
                        """)
                .params(project.id(), role, today, today).query(UUID.class).optional().orElse(null);
    }

    /** Шаблон назви: {type}, {title}, {number}, {service}, {asset}, {shift}. Порожній — «{type}: {service або title}». */
    private static String title(String template, TaskTypeView childType, TaskView parent, String shiftLabel) {
        String typeName = localizedName(childType.name());
        String pattern = template == null || template.isBlank()
                ? "{type}: " + (parent.serviceCode() != null ? "{service}" : "{title}")
                : template;
        String result = pattern
                .replace("{type}", typeName)
                .replace("{title}", parent.title())
                .replace("{number}", "#" + parent.number())
                .replace("{service}", parent.serviceCode() == null ? "" : parent.serviceCode())
                .replace("{asset}", parent.assetCode() == null ? "" : parent.assetCode())
                .replace("{shift}", shiftLabel == null ? "" : shiftLabel)
                .trim();
        return result.length() > 300 ? result.substring(0, 300) : result.isEmpty() ? typeName : result;
    }

    /** Назва мовою запиту (заголовок Accept-Language; без нього — англійська), інакше першою наявною. */
    static String localizedName(Map<String, String> name) {
        String value = name.get(language());
        if (value == null || value.isBlank()) {
            value = name.getOrDefault("en", name.values().stream().findFirst().orElse(""));
        }
        return value;
    }

    /** Мова запиту: перша з Accept-Language, без нього — en. */
    static String language() {
        if (org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
                instanceof org.springframework.web.context.request.ServletRequestAttributes attributes) {
            String header = attributes.getRequest().getHeader("Accept-Language");
            if (header != null && !header.isBlank()) {
                try {
                    List<java.util.Locale.LanguageRange> ranges = java.util.Locale.LanguageRange.parse(header);
                    if (!ranges.isEmpty()) {
                        return ranges.getFirst().getRange().split("-")[0].toLowerCase(java.util.Locale.ROOT);
                    }
                } catch (IllegalArgumentException ignored) {
                    // неправильний заголовок — англійська
                }
            }
        }
        return "en";
    }

    private UUID insertTask(UUID projectId, TaskTypeView type, TaskStatusDefinition status, String title, String description,
                            UUID assigneeId, UUID authorId, UUID serviceId, UUID assetId, UUID parentId,
                            LocalDate plannedStart, LocalDate plannedEnd, Map<String, Object> attributes) {
        UUID companyId = TenantContext.requireCompanyId();
        long number = db.sql("""
                        insert into task_counter (company_id, last_number) values (?, 1)
                        on conflict (company_id) do update set last_number = task_counter.last_number + 1
                        returning last_number
                        """)
                .param(companyId).query(Long.class).single();
        return db.sql("""
                        insert into task (company_id, project_id, task_type_id, number, title, description, status,
                                          status_category, assignee_id, author_id, service_id, asset_id, parent_id,
                                          planned_start, planned_end, attributes)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb))
                        returning id
                        """)
                .params(companyId, projectId, type.id(), number, title, description, status.code(), status.category(),
                        assigneeId, authorId,
                        "service".equals(type.subjectKind()) ? serviceId : null,
                        "asset".equals(type.subjectKind()) ? assetId : null,
                        parentId, plannedStart, plannedEnd, json.writeValueAsString(attributes))
                .query(UUID.class).single();
    }

    // ---------------------------------------------------------------- правила

    /**
     * Задача → послуга: в роботі → in_progress, завершено → completed; форма закриття → поля послуги.
     * Лише головна задача: скасовано → послуга скасована, повернення з done / cancelled повертає й послугу.
     */
    private void syncService(TaskView task, String category, Map<String, Object> closing) {
        UUID serviceId = task.serviceId();
        boolean main = Boolean.TRUE.equals(db.sql("select coalesce(main_task_id = ?, false) from service where id = ?")
                .params(task.id(), serviceId).query(Boolean.class).optional().orElse(false));
        if (main) {
            switch (category) {
                case "cancelled" -> {
                    String reason = cancelReason(task.number());
                    int cancelled = db.sql("""
                                    update service set status = 'cancelled', cancel_reason = ?, version = version + 1,
                                                       updated_at = now()
                                    where id = ? and status in ('planned', 'in_progress')
                                    """)
                            .params(reason, serviceId).update();
                    if (cancelled > 0) {
                        audit.record("service", serviceId, "status", Map.of("to", "cancelled", "byTask", true, "reason", reason));
                    }
                }
                case "new" -> {
                    int reopened = db.sql("""
                                    update service set status = 'planned', cancel_reason = null, actual_end = null,
                                                       version = version + 1, updated_at = now()
                                    where id = ? and status in ('cancelled', 'completed')
                                    """)
                            .param(serviceId).update();
                    if (reopened > 0) {
                        audit.record("service", serviceId, "status", Map.of("to", "planned", "byTask", true));
                    }
                }
                case "active" -> {
                    int reopened = db.sql("""
                                    update service set status = 'in_progress', cancel_reason = null, actual_end = null,
                                                       actual_start = coalesce(actual_start, now()), version = version + 1,
                                                       updated_at = now()
                                    where id = ? and status in ('cancelled', 'completed')
                                    """)
                            .param(serviceId).update();
                    if (reopened > 0) {
                        audit.record("service", serviceId, "status", Map.of("to", "in_progress", "byTask", true));
                    }
                }
                default -> {
                    // done — нижче, як для будь-якої задачі, що синхронізує послугу
                }
            }
        }
        if ("active".equals(category) || "done".equals(category)) {
            int started = db.sql("""
                            update service set status = 'in_progress', actual_start = coalesce(actual_start, now()),
                                               version = version + 1, updated_at = now()
                            where id = ? and status = 'planned'
                            """)
                    .param(serviceId).update();
            if (started > 0) {
                audit.record("service", serviceId, "status", Map.of("from", "planned", "to", "in_progress", "byTask", true));
            }
        }
        if ("done".equals(category)) {
            Map<String, Object> merge = new LinkedHashMap<>();
            if (closing != null && !closing.isEmpty()) {
                List<String> keys = db.sql("""
                                select f ->> 'key'
                                from service s join service_type st on st.id = s.service_type_id,
                                     jsonb_array_elements(st.fields) f
                                where s.id = ?
                                """)
                        .param(serviceId).query(String.class).list();
                closing.forEach((key, value) -> {
                    if (keys.contains(key)) {
                        merge.put(key, value);
                    }
                });
            }
            int completed = db.sql("""
                            update service set status = 'completed', actual_end = now(),
                                               attributes = attributes || cast(? as jsonb), version = version + 1,
                                               updated_at = now()
                            where id = ? and status = 'in_progress'
                            """)
                    .params(json.writeValueAsString(merge), serviceId).update();
            if (completed > 0) {
                audit.record("service", serviceId, "status",
                        Map.of("from", "in_progress", "to", "completed", "byTask", true, "attributes", merge));
            }
        }
    }

    /** Причина скасування послуги мовою запиту. */
    private static String cancelReason(long number) {
        return switch (language()) {
            case "uk" -> "Задачу #" + number + " скасовано";
            case "pt" -> "Tarefa #" + number + " cancelada";
            default -> "Task #" + number + " cancelled";
        };
    }

    /** Чи можна дійти від start до target по зв'язках «блокує» (перевірка циклу). */
    private boolean reaches(UUID start, UUID target) {
        return Boolean.TRUE.equals(db.sql("""
                        with recursive chain(id) as (
                            select cast(:start as uuid)
                            union
                            select l.to_task from task_link l join chain c on l.from_task = c.id where l.kind = 'blocks'
                        )
                        select exists (select 1 from chain where id = cast(:target as uuid))
                        """)
                .param("start", start).param("target", target).query(Boolean.class).single());
    }

    private void checkSubject(TaskTypeView type, ProjectView project, UUID serviceId, UUID assetId) {
        switch (type.subjectKind()) {
            case "service" -> {
                if (serviceId == null) {
                    throw ApiException.validation("serviceId", "required");
                }
                UUID serviceProject = db.sql("select project_id from service where id = ?").param(serviceId)
                        .query(UUID.class).optional()
                        .orElseThrow(() -> ApiException.validation("serviceId", "not_found"));
                if (!serviceProject.equals(project.id())) {
                    throw ApiException.validation("serviceId", "other_project");
                }
            }
            case "asset" -> {
                if (assetId == null) {
                    throw ApiException.validation("assetId", "required");
                }
                Long found = db.sql("select count(*) from asset where id = ? and archived_at is null").param(assetId)
                        .query(Long.class).single();
                if (found == 0) {
                    throw ApiException.validation("assetId", "not_found");
                }
            }
            default -> {
                // без предмета
            }
        }
    }

    /** Виконавець — активний учасник компанії (не портал), який бачить проєкт. */
    private void checkAssignee(ProjectView project, UUID assigneeId) {
        if (assigneeId == null) {
            return;
        }
        Boolean ok = db.sql("""
                        select exists (
                            select 1
                            from membership m
                                     join role r on r.id = m.role_id
                            where m.company_id = app_company_id()
                              and m.user_id = :user
                              and m.status = 'active'
                              and r.code not in ('client_rep', 'client_manager')
                              and (exists (select 1 from role_permission rp
                                           where rp.role_id = m.role_id and rp.permission_code = 'project.access_all')
                                   or exists (select 1 from project_access pa
                                              where pa.project_id = :project and pa.user_id = :user)))
                        """)
                .param("user", assigneeId).param("project", project.id()).query(Boolean.class).single();
        if (!Boolean.TRUE.equals(ok)) {
            throw ApiException.validation("assigneeId", "no_project_access");
        }
    }

    private Actor actor(ProjectView project) {
        Set<String> authorities = new LinkedHashSet<>();
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null) {
            for (GrantedAuthority authority : authentication.getAuthorities()) {
                authorities.add(authority.getAuthority());
            }
        }
        return new Actor(authorities.contains("project.manage"), roles.isSupervisor(project), currentUserId());
    }

    private ProjectView openProject(UUID projectId) {
        ProjectView project = projects.get(projectId);
        if ("closed".equals(project.status())) {
            throw ApiException.conflict("project_closed");
        }
        return project;
    }

    private void history(UUID taskId, String action, Map<String, Object> changes) {
        db.sql("insert into task_history (company_id, task_id, user_id, action, changes) values (?, ?, ?, ?, cast(? as jsonb))")
                .params(TenantContext.requireCompanyId(), taskId, currentUserId(), action,
                        changes == null ? null : json.writeValueAsString(changes))
                .update();
    }

    private static void appendFilter(Filter filter, StringBuilder where, List<Object> params) {
        if (filter.category() != null && !filter.category().isBlank()) {
            if ("open".equals(filter.category())) {
                where.append(" and t.status_category in ('new', 'active')");
            } else {
                where.append(" and t.status_category = ?");
                params.add(filter.category());
            }
        }
        if (filter.assigneeId() != null) {
            where.append(" and t.assignee_id = ?");
            params.add(filter.assigneeId());
        }
        if (filter.typeId() != null) {
            where.append(" and t.task_type_id = ?");
            params.add(filter.typeId());
        }
        if (filter.serviceId() != null) {
            where.append(" and t.service_id = ?");
            params.add(filter.serviceId());
        }
    }

    private static void checkDates(LocalDate start, LocalDate end) {
        if (start != null && end != null && end.isBefore(start)) {
            throw ApiException.validation("plannedEnd", "before_start");
        }
    }

    private static Map<String, Object> validAttributes(List<FieldDefinition> fields, Map<String, Object> input, String prefix) {
        AttributeValidator.Result result = AttributeValidator.validate(fields, input == null ? Map.of() : input);
        if (!result.isValid()) {
            Map<String, String> errors = new LinkedHashMap<>();
            result.errors().forEach((key, code) -> errors.put(prefix + "." + key, code));
            throw ApiException.validation(errors);
        }
        return new LinkedHashMap<>(result.values());
    }

    private static boolean sameMaps(Map<String, Object> a, Map<String, Object> b) {
        Set<String> keys = new LinkedHashSet<>(a.keySet());
        keys.addAll(b.keySet());
        return keys.stream().allMatch(k -> same(a.get(k), b.get(k)));
    }

    private static boolean same(Object a, Object b) {
        if (a instanceof Number && b instanceof Number) {
            return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString())) == 0;
        }
        return Objects.equals(a, b);
    }

    private static Map<String, Object> changes(TaskView before, TaskView after) {
        Map<String, Object> changes = new LinkedHashMap<>();
        compare(changes, "title", before.title(), after.title());
        compare(changes, "description", before.description(), after.description());
        compare(changes, "assignee", before.assigneeName(), after.assigneeName());
        compare(changes, "service", before.serviceCode(), after.serviceCode());
        compare(changes, "asset", before.assetCode(), after.assetCode());
        compare(changes, "plannedStart", before.plannedStart(), after.plannedStart());
        compare(changes, "plannedEnd", before.plannedEnd(), after.plannedEnd());
        compare(changes, "forecastEnd", before.forecastEnd(), after.forecastEnd());
        Set<String> keys = new LinkedHashSet<>(before.attributes().keySet());
        keys.addAll(after.attributes().keySet());
        for (String key : keys) {
            compare(changes, "attributes." + key, before.attributes().get(key), after.attributes().get(key));
        }
        return changes;
    }

    private static void compare(Map<String, Object> changes, String key, Object before, Object after) {
        if (!same(before, after)) {
            changes.put(key, Arrays.asList(before == null ? null : before.toString(), after == null ? null : after.toString()));
        }
    }

    private List<TaskRef> refs(String sql, UUID id) {
        return db.sql(sql).param(id).query(TaskWorkflow::ref).list();
    }

    private static TaskRef ref(ResultSet rs, int n) throws SQLException {
        return new TaskRef(rs.getObject("link_id", UUID.class), rs.getObject("id", UUID.class), rs.getLong("number"),
                rs.getString("title"), rs.getString("status"), rs.getString("status_category"));
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

    private TaskView map(ResultSet rs, int rowNum) throws SQLException {
        Long parentNumber = rs.getObject("parent_number") == null ? null : rs.getLong("parent_number");
        return new TaskView(
                rs.getObject("id", UUID.class),
                rs.getLong("number"),
                rs.getObject("project_id", UUID.class),
                rs.getString("project_code"),
                rs.getObject("task_type_id", UUID.class),
                rs.getString("type_code"),
                rs.getString("title"),
                rs.getString("description"),
                rs.getString("status"),
                rs.getString("status_category"),
                rs.getObject("assignee_id", UUID.class),
                rs.getString("assignee_name"),
                rs.getObject("author_id", UUID.class),
                rs.getString("author_name"),
                rs.getObject("service_id", UUID.class),
                rs.getString("service_code"),
                rs.getObject("asset_id", UUID.class),
                rs.getString("asset_code"),
                rs.getObject("parent_id", UUID.class),
                parentNumber,
                rs.getObject("work_shift_id", UUID.class),
                rs.getObject("planned_start", LocalDate.class),
                rs.getObject("planned_end", LocalDate.class),
                rs.getObject("forecast_end", LocalDate.class),
                rs.getObject("actual_start", OffsetDateTime.class),
                rs.getObject("actual_end", OffsetDateTime.class),
                json.readValue(rs.getString("attributes"), MAP),
                rs.getString("closing_values") == null ? null : json.readValue(rs.getString("closing_values"), MAP),
                rs.getInt("open_blockers"),
                rs.getInt("children_total"),
                rs.getInt("children_open"),
                rs.getInt("version"),
                rs.getObject("created_at", OffsetDateTime.class));
    }
}
