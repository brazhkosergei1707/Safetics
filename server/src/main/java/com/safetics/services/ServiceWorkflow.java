package com.safetics.services;

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
import com.safetics.core.audit.AuditService;
import com.safetics.core.tenancy.TenantContext;
import com.safetics.core.web.ApiException;
import com.safetics.customers.CustomerDtos.CustomerView;
import com.safetics.customers.CustomerService;
import com.safetics.projects.ProjectDtos.ProjectView;
import com.safetics.projects.ProjectRoles;
import com.safetics.projects.ProjectService;
import com.safetics.services.ServiceDtos.ServiceCreateRequest;
import com.safetics.services.ServiceDtos.ServiceTypeView;
import com.safetics.services.ServiceDtos.ServiceUpdateRequest;
import com.safetics.services.ServiceDtos.ServiceView;
import com.safetics.tasks.TaskWorkflow;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Послуги проєкту і їхній статус: заплановано → в роботі → завершено; скасовано.
 *
 * <p>Хто що може (видимість проєкту — RLS):
 * <ul>
 *   <li>менеджер (project.manage) — усе, зокрема повернути завершену чи скасовану;</li>
 *   <li>супервайзер проєкту (посада з роллю supervisor) — створювати, змінювати, брати в роботу,
 *       завершувати, скасовувати;</li>
 *   <li>планувальник (service.plan без project.manage — представник і менеджер замовника) —
 *       додавати, змінювати й скасовувати лише заплановані.</li>
 * </ul>
 *
 * <p>У кожної послуги є головна задача (тип — з типу послуги): створюється разом із послугою з тими
 * самими датами й відповідальним (за замовчуванням — супервайзер проєкту). Статус послуги веде
 * головна задача: «почати» / «завершити» / «скасувати» / «повернути» послугу — це перехід головної
 * задачі, а синхронізація задачі оновлює послугу. Без головної задачі послуги не буває.
 */
@Service
public class ServiceWorkflow {

    private static final TypeReference<Map<String, Object>> ATTRIBUTES = new TypeReference<>() {
    };

    private static final String SELECT = """
            select s.id, s.project_id, s.service_type_id, t.code as type_code, s.code, s.name,
                   coalesce(s.customer_id, p.customer_id) as customer_id, c.name as customer_name,
                   s.status, s.attributes, s.planned_start, s.planned_end, s.actual_start, s.actual_end,
                   s.cancel_reason, mt.id as main_task_id, mt.number as main_task_number, mt.task_type_id as main_task_type_id,
                   mt.assignee_id, au.full_name as assignee_name, s.version, s.created_at
            from service s
                     join service_type t on t.id = s.service_type_id
                     join project p on p.id = s.project_id
                     left join customer c on c.id = coalesce(s.customer_id, p.customer_id)
                     left join task mt on mt.id = s.main_task_id
                     left join app_user au on au.id = mt.assignee_id
            """;

    /** Що може поточний користувач на цьому проєкті. */
    private record Actor(boolean manage, boolean supervisor, boolean plan) {
        boolean worksOnSite() {
            return manage || supervisor;
        }

        boolean any() {
            return manage || supervisor || plan;
        }
    }

    private final JdbcClient db;
    private final JsonMapper json;
    private final ProjectService projects;
    private final ProjectRoles roles;
    private final ServiceTypeService types;
    private final CustomerService customers;
    private final TaskWorkflow tasks;
    private final AuditService audit;

    public ServiceWorkflow(JdbcClient db, JsonMapper json, ProjectService projects, ProjectRoles roles,
                           ServiceTypeService types, CustomerService customers, TaskWorkflow tasks, AuditService audit) {
        this.db = db;
        this.json = json;
        this.projects = projects;
        this.roles = roles;
        this.types = types;
        this.customers = customers;
        this.tasks = tasks;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- читання

    public List<ServiceView> list(UUID projectId, String status, UUID typeId) {
        projects.get(projectId);
        StringBuilder where = new StringBuilder(" where s.project_id = ?");
        List<Object> params = new ArrayList<>(List.of(projectId));
        if (status != null && !status.isBlank()) {
            where.append(" and s.status = ?");
            params.add(status);
        }
        if (typeId != null) {
            where.append(" and s.service_type_id = ?");
            params.add(typeId);
        }
        return db.sql(SELECT + where + """
                         order by case s.status when 'in_progress' then 0 when 'planned' then 1 when 'completed' then 2 else 3 end,
                                  s.planned_start nulls last, s.code
                        """)
                .params(params).query(this::map).list();
    }

    public ServiceView get(UUID id) {
        return find(id).orElseThrow(ApiException::notFound);
    }

    public Optional<ServiceView> find(UUID id) {
        return db.sql(SELECT + " where s.id = ?").param(id).query(this::map).optional();
    }

    // ---------------------------------------------------------------- зміни

    @Transactional
    public ServiceView create(UUID projectId, ServiceCreateRequest request) {
        ProjectView project = openProject(projectId);
        Actor actor = actor(project);
        if (!actor.any()) {
            throw forbidden();
        }
        ServiceTypeView type = types.find(request.typeId())
                .orElseThrow(() -> ApiException.validation("typeId", "not_found"));
        if (type.archived()) {
            throw ApiException.validation("typeId", "archived");
        }
        if (type.taskTypeId() == null) {
            throw ApiException.validation("typeId", "no_task_type");
        }
        UUID customerId = customer(project, request.customerId(), null);
        checkDates(request.plannedStart(), request.plannedEnd());
        Map<String, Object> attributes = validAttributes(type, request.attributes());

        UUID id = db.sql("""
                        insert into service (company_id, project_id, service_type_id, code, name, customer_id, attributes,
                                             planned_start, planned_end, created_by)
                        values (?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?, ?)
                        returning id
                        """)
                .params(TenantContext.requireCompanyId(), projectId, type.id(), request.code().trim(),
                        blankToNull(request.name()), customerId, json.writeValueAsString(attributes),
                        request.plannedStart(), request.plannedEnd(), currentUserId())
                .query(UUID.class).single();
        audit.record("service", id, "create", Map.of("projectId", projectId, "type", type.code(), "code", request.code().trim()));
        // Замовник відповідального не обирає — супервайзер проєкту
        UUID assignee = actor.worksOnSite() ? request.assigneeId() : null;
        UUID mainTask = tasks.createMainTask(project, type.taskTypeId(), id, request.code().trim(), assignee,
                request.plannedStart(), request.plannedEnd());
        db.sql("update service set main_task_id = ? where id = ?").params(mainTask, id).update();
        return get(id);
    }

    @Transactional
    public ServiceView update(UUID id, ServiceUpdateRequest request) {
        ServiceView existing = get(id);
        ProjectView project = openProject(existing.projectId());
        Actor actor = actor(project);
        requireEditable(existing, actor);
        if (request.version() != existing.version()) {
            throw ApiException.conflict("stale_version");
        }
        ServiceTypeView type = types.get(existing.typeId());
        UUID customerId = customer(project, request.customerId(), ownCustomer(existing, project));
        checkDates(request.plannedStart(), request.plannedEnd());
        Map<String, Object> attributes = validAttributes(type, request.attributes());
        // Значення полів, яких уже немає в схемі типу, не губляться
        existing.attributes().forEach((key, value) -> {
            if (type.fields().stream().noneMatch(f -> f.key().equals(key))) {
                attributes.putIfAbsent(key, value);
            }
        });

        int updated = db.sql("""
                        update service
                        set code = ?, name = ?, customer_id = ?, attributes = cast(? as jsonb), planned_start = ?,
                            planned_end = ?, version = version + 1, updated_at = now()
                        where id = ? and version = ?
                        """)
                .params(request.code().trim(), blankToNull(request.name()), customerId, json.writeValueAsString(attributes),
                        request.plannedStart(), request.plannedEnd(), id, request.version())
                .update();
        if (updated == 0) {
            throw ApiException.conflict("stale_version");
        }
        UUID mainTask = mainTaskId(id);
        if (mainTask != null) {
            tasks.syncMainTask(mainTask, request.plannedStart(), request.plannedEnd(), actor.worksOnSite(),
                    request.assigneeId());
        }
        ServiceView saved = get(id);
        Map<String, Object> changes = changes(existing, saved);
        if (!changes.isEmpty()) {
            audit.record("service", id, "update", changes);
        }
        return saved;
    }

    /** Заплановано → в роботі: головна задача — в роботу. Фактичний початок — зараз. */
    @Transactional
    public ServiceView start(UUID id) {
        ServiceView existing = get(id);
        ProjectView project = openProject(existing.projectId());
        if (!actor(project).worksOnSite()) {
            throw forbidden();
        }
        requireStatus(existing, "planned");
        return viaMainTask(existing, "active", null, "in_progress", "actual_start = now()", null);
    }

    /**
     * В роботі → завершено: головна задача — виконано (closingValues — її форма закриття; поля з
     * ключами полів послуги записуються в послугу). Фактичний кінець — зараз.
     */
    @Transactional
    public ServiceView complete(UUID id, Map<String, Object> closingValues) {
        ServiceView existing = get(id);
        ProjectView project = openProject(existing.projectId());
        if (!actor(project).worksOnSite()) {
            throw forbidden();
        }
        requireStatus(existing, "in_progress");
        return viaMainTask(existing, "done", closingValues, "completed", "actual_end = now()", null);
    }

    /** Заплановано / в роботі → скасовано (з причиною), і головна задача теж. Планувальник — лише заплановані. */
    @Transactional
    public ServiceView cancel(UUID id, String reason) {
        ServiceView existing = get(id);
        ProjectView project = openProject(existing.projectId());
        Actor actor = actor(project);
        requireEditable(existing, actor);
        transition(existing, existing.status(), "cancelled", "cancel_reason = ?", reason.trim());
        UUID mainTask = mainTaskId(id);
        if (mainTask != null) {
            tasks.moveMainTask(mainTask, "cancelled", null);
        }
        return get(id);
    }

    /** Лише менеджер: завершена → в роботі, скасована → заплановано; головна задача повертається теж. */
    @Transactional
    public ServiceView reopen(UUID id) {
        ServiceView existing = get(id);
        ProjectView project = openProject(existing.projectId());
        if (!actor(project).manage()) {
            throw forbidden();
        }
        return switch (existing.status()) {
            case "completed" -> viaMainTask(existing, "active", null, "in_progress", "actual_end = null", null);
            case "cancelled" -> viaMainTask(existing, "new", null, "planned", "cancel_reason = null", null);
            default -> throw ApiException.conflict("invalid_transition");
        };
    }

    /**
     * Перехід головної задачі в категорію; синхронізація задачі оновлює послугу. Якщо послуга після
     * цього ще не в потрібному статусі (задача вже була в категорії, тип без синхронізації) — напряму.
     */
    private ServiceView viaMainTask(ServiceView existing, String taskCategory, Map<String, Object> closingValues,
                                    String to, String extraSet, String extraParam) {
        UUID mainTask = mainTaskId(existing.id());
        if (mainTask != null) {
            tasks.moveMainTask(mainTask, taskCategory, closingValues);
        }
        String status = db.sql("select status from service where id = ?").param(existing.id()).query(String.class).single();
        if (!to.equals(status)) {
            return transition(existing, existing.status(), to, extraSet, extraParam);
        }
        return get(existing.id());
    }

    private UUID mainTaskId(UUID serviceId) {
        return db.sql("select main_task_id from service where id = ?").param(serviceId).query(UUID.class).optional()
                .orElse(null);
    }

    private static void requireStatus(ServiceView service, String status) {
        if (!status.equals(service.status())) {
            throw ApiException.conflict("invalid_transition");
        }
    }

    // ---------------------------------------------------------------- правила

    private ServiceView transition(ServiceView existing, String from, String to, String extraSet, String extraParam) {
        if (!from.equals(existing.status())) {
            throw ApiException.conflict("invalid_transition");
        }
        List<Object> params = new ArrayList<>();
        params.add(to);
        if (extraParam != null) {
            params.add(extraParam);
        }
        params.add(existing.id());
        params.add(from);
        int updated = db.sql("update service set status = ?, " + extraSet
                        + ", version = version + 1, updated_at = now() where id = ? and status = ?")
                .params(params).update();
        if (updated == 0) {
            throw ApiException.conflict("invalid_transition");
        }
        audit.record("service", existing.id(), "status",
                extraParam == null ? Map.of("from", from, "to", to) : Map.of("from", from, "to", to, "reason", extraParam));
        return get(existing.id());
    }

    /** Змінювати можна заплановані й у роботі; планувальнику (замовнику) — лише заплановані. */
    private static void requireEditable(ServiceView service, Actor actor) {
        if (!actor.any()) {
            throw forbidden();
        }
        boolean open = "planned".equals(service.status()) || "in_progress".equals(service.status());
        if (!open) {
            throw ApiException.conflict("service_closed");
        }
        if (!actor.worksOnSite() && !"planned".equals(service.status())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "only_planned", Map.of());
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
        return new Actor(authorities.contains("project.manage"), roles.isSupervisor(project),
                authorities.contains("service.plan"));
    }

    private ProjectView openProject(UUID projectId) {
        ProjectView project = projects.get(projectId);
        if ("closed".equals(project.status())) {
            throw ApiException.conflict("project_closed");
        }
        return project;
    }

    /** Власний замовник послуги — лише якщо в проєкті замовника немає. */
    private UUID customer(ProjectView project, UUID requested, UUID current) {
        if (requested == null) {
            return null;
        }
        if (project.customerId() != null) {
            if (requested.equals(project.customerId())) {
                return null;
            }
            throw ApiException.validation("customerId", "project_has_customer");
        }
        if (!requested.equals(current)) {
            CustomerView customer = customers.find(requested)
                    .orElseThrow(() -> ApiException.validation("customerId", "not_found"));
            if (customer.archived()) {
                throw ApiException.validation("customerId", "archived");
            }
        }
        return requested;
    }

    private static UUID ownCustomer(ServiceView service, ProjectView project) {
        return project.customerId() == null ? service.customerId() : null;
    }

    private static void checkDates(LocalDate start, LocalDate end) {
        if (start != null && end != null && end.isBefore(start)) {
            throw ApiException.validation("plannedEnd", "before_start");
        }
    }

    private static Map<String, Object> validAttributes(ServiceTypeView type, Map<String, Object> input) {
        AttributeValidator.Result result = AttributeValidator.validate(type.fields(), input);
        if (!result.isValid()) {
            Map<String, String> errors = new LinkedHashMap<>();
            result.errors().forEach((key, code) -> errors.put("attributes." + key, code));
            throw ApiException.validation(errors);
        }
        return new LinkedHashMap<>(result.values());
    }

    private static Map<String, Object> changes(ServiceView before, ServiceView after) {
        Map<String, Object> changes = new LinkedHashMap<>();
        compare(changes, "code", before.code(), after.code());
        compare(changes, "name", before.name(), after.name());
        compare(changes, "customerId", before.customerId(), after.customerId());
        compare(changes, "plannedStart", before.plannedStart(), after.plannedStart());
        compare(changes, "plannedEnd", before.plannedEnd(), after.plannedEnd());
        Set<String> keys = new LinkedHashSet<>(before.attributes().keySet());
        keys.addAll(after.attributes().keySet());
        for (String key : keys) {
            compare(changes, "attributes." + key, before.attributes().get(key), after.attributes().get(key));
        }
        return changes;
    }

    private static void compare(Map<String, Object> changes, String key, Object before, Object after) {
        boolean same = before instanceof Number && after instanceof Number
                ? new BigDecimal(before.toString()).compareTo(new BigDecimal(after.toString())) == 0
                : Objects.equals(before, after);
        if (!same) {
            changes.put(key, Arrays.asList(before == null ? null : before.toString(), after == null ? null : after.toString()));
        }
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

    private ServiceView map(ResultSet rs, int rowNum) throws SQLException {
        return new ServiceView(
                rs.getObject("id", UUID.class),
                rs.getObject("project_id", UUID.class),
                rs.getObject("service_type_id", UUID.class),
                rs.getString("type_code"),
                rs.getString("code"),
                rs.getString("name"),
                rs.getObject("customer_id", UUID.class),
                rs.getString("customer_name"),
                rs.getString("status"),
                json.readValue(rs.getString("attributes"), ATTRIBUTES),
                rs.getObject("planned_start", LocalDate.class),
                rs.getObject("planned_end", LocalDate.class),
                rs.getObject("actual_start", OffsetDateTime.class),
                rs.getObject("actual_end", OffsetDateTime.class),
                rs.getString("cancel_reason"),
                rs.getObject("main_task_id", UUID.class),
                rs.getObject("main_task_number") == null ? null : rs.getLong("main_task_number"),
                rs.getObject("main_task_type_id", UUID.class),
                rs.getObject("assignee_id", UUID.class),
                rs.getString("assignee_name"),
                rs.getInt("version"),
                rs.getObject("created_at", OffsetDateTime.class));
    }
}
