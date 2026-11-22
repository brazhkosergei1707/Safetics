package com.safetics.projects;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.safetics.assets.AssetDtos.AssetView;
import com.safetics.assets.AssetService;
import com.safetics.core.audit.AuditService;
import com.safetics.core.tenancy.TenantContext;
import com.safetics.core.web.ApiException;
import com.safetics.personnel.PersonnelDtos.PersonnelRequest;
import com.safetics.personnel.PersonnelDtos.PersonnelView;
import com.safetics.personnel.PersonnelDtos.PositionView;
import com.safetics.personnel.PersonnelService;
import com.safetics.personnel.PositionService;
import com.safetics.projects.AssignmentDtos.AssignmentDatesRequest;
import com.safetics.projects.AssignmentDtos.AvailableEquipmentView;
import com.safetics.projects.AssignmentDtos.CrewMemberRequest;
import com.safetics.projects.AssignmentDtos.EquipmentAssignmentRequest;
import com.safetics.projects.AssignmentDtos.EquipmentAssignmentView;
import com.safetics.projects.AssignmentDtos.PersonnelAssignmentRequest;
import com.safetics.projects.AssignmentDtos.PersonnelAssignmentUpdateRequest;
import com.safetics.projects.AssignmentDtos.PersonnelAssignmentView;
import com.safetics.projects.ProjectDtos.ProjectView;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Призначення людей і обладнання на проєкт. Людина чи основне обладнання не можуть бути на двох
 * проєктах одночасно; допоміжне (водовозка, вантажівка) може, але не двічі на тому самому проєкті.
 * Це перевіряє база (exclude), тому перекриття з проєктом, якого користувач не бачить, теж не
 * пройде: відповідь 409 overlap без подробиць про чужий проєкт.
 *
 * <p>Призначення людини з входом дає їй доступ до проєкту. Призначається лише обладнання верхнього
 * рівня: вкладене (насос бурової) їде разом із батьківським.
 */
@Service
public class AssignmentService {

    private static final TypeReference<Map<String, String>> NAME = new TypeReference<>() {
    };

    private static final String PERSONNEL_SELECT = """
            select a.id, a.project_id, a.personnel_id, p.full_name, p.employment_type, p.organization, a.position_id,
                   pos.name as position_name, pos.project_role, p.user_id is not null as has_login, p.user_id,
                   a.start_date, a.end_date
            from personnel_assignment a
                     join personnel p on p.id = a.personnel_id
                     join position pos on pos.id = a.position_id
            """;

    private static final String EQUIPMENT_SELECT = """
            select a.id, a.project_id, a.asset_id, s.code as asset_code, s.name as asset_name, t.code as type_code,
                   t.name as type_name, t.category, a.start_date, a.end_date
            from equipment_assignment a
                     join asset s on s.id = a.asset_id
                     join asset_type t on t.id = s.asset_type_id
            """;

    private final JdbcClient db;
    private final JsonMapper json;
    private final ProjectService projects;
    private final PersonnelService personnel;
    private final PositionService positions;
    private final AssetService assets;
    private final AuditService audit;
    private final ProjectRoles roles;

    public AssignmentService(JdbcClient db, JsonMapper json, ProjectService projects, PersonnelService personnel,
                             PositionService positions, AssetService assets, AuditService audit, ProjectRoles roles) {
        this.roles = roles;
        this.db = db;
        this.json = json;
        this.projects = projects;
        this.personnel = personnel;
        this.positions = positions;
        this.assets = assets;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- люди

    public List<PersonnelAssignmentView> personnel(UUID projectId) {
        ProjectView project = projects.get(projectId);
        LocalDate today = projects.today(project);
        return db.sql(PERSONNEL_SELECT + " where a.project_id = ? order by a.start_date desc, lower(p.full_name)")
                .param(projectId).query((rs, n) -> mapPersonnel(rs, today)).list();
    }

    /** Історія призначень виконавця (на видимих користувачу проєктах). */
    public List<PersonnelAssignmentView> ofPersonnel(UUID personnelId) {
        personnel.get(personnelId);
        LocalDate today = LocalDate.now();
        return db.sql(PERSONNEL_SELECT + " where a.personnel_id = ? order by a.start_date desc")
                .param(personnelId).query((rs, n) -> mapPersonnel(rs, today)).list();
    }

    @Transactional
    public PersonnelAssignmentView assignPersonnel(UUID projectId, PersonnelAssignmentRequest request) {
        ProjectView project = openProject(projectId);
        PersonnelView person = personnel.find(request.personnelId())
                .orElseThrow(() -> ApiException.validation("personnelId", "not_found"));
        if (person.archived()) {
            throw ApiException.validation("personnelId", "archived");
        }
        UUID positionId = request.positionId() != null ? request.positionId() : person.positionId();
        if (positionId == null) {
            throw ApiException.validation("positionId", "required");
        }
        activePosition(positionId);
        checkDates(project, request.startDate(), request.endDate());

        UUID id = insertPersonnelAssignment(project, person, positionId, request.startDate(), request.endDate());
        return getPersonnelAssignment(projectId, id);
    }

    /**
     * Супервайзер (або менеджер) додає людину без входу на проєкт. Супервайзером вважається той, хто
     * зараз призначений на цей проєкт на посаду з роллю supervisor.
     */
    @Transactional
    public PersonnelAssignmentView addCrewMember(UUID projectId, CrewMemberRequest request, boolean canManage) {
        ProjectView project = openProject(projectId);
        PositionView position = activePosition(request.positionId());
        if (!canManage) {
            if (!roles.isSupervisor(project)) {
                throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "not_project_supervisor", Map.of());
            }
            if (!position.supervisorCanAdd()) {
                throw ApiException.validation("positionId", "not_allowed_for_supervisor");
            }
        }
        checkDates(project, request.startDate(), request.endDate());
        PersonnelView person = personnel.create(new PersonnelRequest(request.fullName(), request.phone(),
                request.employmentType(), request.organization(), position.id(), null, null));
        UUID id = insertPersonnelAssignment(project, person, position.id(), request.startDate(), request.endDate());
        return getPersonnelAssignment(projectId, id);
    }

    @Transactional
    public PersonnelAssignmentView updatePersonnelAssignment(UUID projectId, UUID id, PersonnelAssignmentUpdateRequest request) {
        ProjectView project = openProject(projectId);
        PersonnelAssignmentView existing = getPersonnelAssignment(projectId, id);
        activePosition(request.positionId());
        checkDates(project, request.startDate(), request.endDate());
        withOverlapCheck(() -> db.sql("""
                        update personnel_assignment set position_id = ?, start_date = ?, end_date = ?, updated_at = now()
                        where id = ?
                        """)
                .params(request.positionId(), request.startDate(), request.endDate(), id).update());
        audit.record("project", projectId, "update_personnel_assignment", Map.of(
                "assignmentId", id, "personnelId", existing.personnelId(),
                "before", dates(existing.startDate(), existing.endDate()),
                "after", dates(request.startDate(), request.endDate())));
        return getPersonnelAssignment(projectId, id);
    }

    public void deletePersonnelAssignment(UUID projectId, UUID id) {
        openProject(projectId);
        PersonnelAssignmentView existing = getPersonnelAssignment(projectId, id);
        db.sql("delete from personnel_assignment where id = ?").param(id).update();
        audit.record("project", projectId, "delete_personnel_assignment", Map.of(
                "personnelId", existing.personnelId(), "dates", dates(existing.startDate(), existing.endDate())));
    }

    // ---------------------------------------------------------------- обладнання

    public List<EquipmentAssignmentView> equipment(UUID projectId) {
        ProjectView project = projects.get(projectId);
        LocalDate today = projects.today(project);
        return db.sql(EQUIPMENT_SELECT + " where a.project_id = ? order by t.category desc, s.code, a.start_date desc")
                .param(projectId).query((rs, n) -> mapEquipment(rs, today)).list();
    }

    /**
     * Обладнання верхнього рівня на період: вільне чи зайняте. Основне зайняте, якщо на ці дати стоїть
     * на будь-якому проєкті; допоміжне — лише якщо вже стоїть на цьому проєкті.
     */
    public List<AvailableEquipmentView> available(UUID projectId, LocalDate start, LocalDate end) {
        ProjectView project = projects.get(projectId);
        LocalDate from = start != null ? start : projects.today(project);
        return db.sql("""
                        select s.id, s.code, s.name, t.code as type_code, t.name as type_name, t.category,
                               b.asset_id is null as available, coalesce(b.same_project, false) as on_this_project,
                               b.project_code, b.end_date
                        from asset s
                                 join asset_type t on t.id = s.asset_type_id
                                 left join lateral (
                            select ea.asset_id, ea.end_date, p.code as project_code, ea.project_id = :projectId as same_project
                            from equipment_assignment ea
                                     left join project p on p.id = ea.project_id
                            where ea.asset_id = s.id
                              and daterange(ea.start_date, ea.end_date, '[]') && daterange(:start, cast(:end as date), '[]')
                              and (ea.project_id = :projectId or (t.category = 'primary' and ea.exclusive))
                            order by ea.project_id = :projectId desc, ea.start_date
                            limit 1) b on true
                        where s.parent_id is null and s.archived_at is null
                        order by t.category desc, t.code, s.code
                        """)
                .param("projectId", projectId)
                .param("start", from)
                .param("end", end)
                .query((rs, n) -> new AvailableEquipmentView(
                        rs.getObject("id", UUID.class),
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getString("type_code"),
                        json.readValue(rs.getString("type_name"), NAME),
                        rs.getString("category"),
                        rs.getBoolean("available"),
                        rs.getBoolean("on_this_project"),
                        rs.getString("project_code"),
                        rs.getObject("end_date", LocalDate.class)))
                .list();
    }

    @Transactional
    public EquipmentAssignmentView assignEquipment(UUID projectId, EquipmentAssignmentRequest request) {
        ProjectView project = openProject(projectId);
        AssetView asset = assets.find(request.assetId())
                .orElseThrow(() -> ApiException.validation("assetId", "not_found"));
        if (asset.archived()) {
            throw ApiException.validation("assetId", "archived");
        }
        if (asset.parentId() != null) {
            throw ApiException.validation("assetId", "nested_equipment");
        }
        checkDates(project, request.startDate(), request.endDate());
        UUID id = withOverlapCheck(() -> db.sql("""
                        insert into equipment_assignment (company_id, project_id, asset_id, start_date, end_date, created_by)
                        values (?, ?, ?, ?, ?, ?)
                        returning id
                        """)
                .params(TenantContext.requireCompanyId(), projectId, request.assetId(), request.startDate(),
                        request.endDate(), currentUserId())
                .query(UUID.class).single());
        audit.record("project", projectId, "assign_equipment", Map.of(
                "assetId", request.assetId(), "code", asset.code(), "dates", dates(request.startDate(), request.endDate())));
        return getEquipmentAssignment(projectId, id);
    }

    @Transactional
    public EquipmentAssignmentView updateEquipmentAssignment(UUID projectId, UUID id, AssignmentDatesRequest request) {
        ProjectView project = openProject(projectId);
        EquipmentAssignmentView existing = getEquipmentAssignment(projectId, id);
        checkDates(project, request.startDate(), request.endDate());
        withOverlapCheck(() -> db.sql("""
                        update equipment_assignment set start_date = ?, end_date = ?, updated_at = now() where id = ?
                        """)
                .params(request.startDate(), request.endDate(), id).update());
        audit.record("project", projectId, "update_equipment_assignment", Map.of(
                "assetId", existing.assetId(),
                "before", dates(existing.startDate(), existing.endDate()),
                "after", dates(request.startDate(), request.endDate())));
        return getEquipmentAssignment(projectId, id);
    }

    public void deleteEquipmentAssignment(UUID projectId, UUID id) {
        openProject(projectId);
        EquipmentAssignmentView existing = getEquipmentAssignment(projectId, id);
        db.sql("delete from equipment_assignment where id = ?").param(id).update();
        audit.record("project", projectId, "delete_equipment_assignment", Map.of(
                "assetId", existing.assetId(), "dates", dates(existing.startDate(), existing.endDate())));
    }

    // ---------------------------------------------------------------- правила

    private UUID insertPersonnelAssignment(ProjectView project, PersonnelView person, UUID positionId,
                                           LocalDate start, LocalDate end) {
        UUID companyId = TenantContext.requireCompanyId();
        UUID id = withOverlapCheck(() -> db.sql("""
                        insert into personnel_assignment (company_id, project_id, personnel_id, position_id, start_date,
                                                          end_date, created_by)
                        values (?, ?, ?, ?, ?, ?, ?)
                        returning id
                        """)
                .params(companyId, project.id(), person.id(), positionId, start, end, currentUserId())
                .query(UUID.class).single());
        // Людина з входом бачить проєкт, на який її призначено
        if (person.userId() != null && person.loginRole() != null) {
            db.sql("""
                            insert into project_access (company_id, project_id, user_id, granted_by)
                            values (?, ?, ?, ?)
                            on conflict do nothing
                            """)
                    .params(companyId, project.id(), person.userId(), currentUserId()).update();
        }
        audit.record("project", project.id(), "assign_personnel", Map.of(
                "personnelId", person.id(), "fullName", person.fullName(), "positionId", positionId,
                "dates", dates(start, end)));
        return id;
    }

    private ProjectView openProject(UUID projectId) {
        ProjectView project = projects.get(projectId);
        if ("closed".equals(project.status())) {
            throw ApiException.conflict("project_closed");
        }
        return project;
    }

    private PositionView activePosition(UUID positionId) {
        PositionView position = positions.find(positionId)
                .orElseThrow(() -> ApiException.validation("positionId", "not_found"));
        if (position.archived()) {
            throw ApiException.validation("positionId", "archived");
        }
        return position;
    }

    /** Призначення — у межах дат проєкту. */
    private static void checkDates(ProjectView project, LocalDate start, LocalDate end) {
        if (end != null && end.isBefore(start)) {
            throw ApiException.validation("endDate", "before_start");
        }
        if (start.isBefore(project.startDate())) {
            throw ApiException.validation("startDate", "before_project_start");
        }
        if (project.endDate() != null && (end == null ? start.isAfter(project.endDate()) : end.isAfter(project.endDate()))) {
            throw ApiException.validation(end == null ? "startDate" : "endDate", "after_project_end");
        }
    }

    private static <T> T withOverlapCheck(java.util.function.Supplier<T> action) {
        try {
            return action.get();
        } catch (DataIntegrityViolationException e) {
            if (String.valueOf(e.getMessage()).contains("_no_overlap")) {
                throw ApiException.conflict("overlap");
            }
            throw e;
        }
    }

    private PersonnelAssignmentView getPersonnelAssignment(UUID projectId, UUID id) {
        ProjectView project = projects.get(projectId);
        LocalDate today = projects.today(project);
        Optional<PersonnelAssignmentView> view = db.sql(PERSONNEL_SELECT + " where a.id = ? and a.project_id = ?")
                .params(id, projectId).query((rs, n) -> mapPersonnel(rs, today)).optional();
        return view.orElseThrow(ApiException::notFound);
    }

    private EquipmentAssignmentView getEquipmentAssignment(UUID projectId, UUID id) {
        ProjectView project = projects.get(projectId);
        LocalDate today = projects.today(project);
        return db.sql(EQUIPMENT_SELECT + " where a.id = ? and a.project_id = ?")
                .params(id, projectId).query((rs, n) -> mapEquipment(rs, today)).optional()
                .orElseThrow(ApiException::notFound);
    }

    private static Map<String, String> dates(LocalDate start, LocalDate end) {
        return Map.of("start", start.toString(), "end", end == null ? "" : end.toString());
    }

    private static UUID currentUserId() {
        return TenantContext.current().map(TenantContext.Tenant::userId).orElse(null);
    }

    private static boolean active(LocalDate start, LocalDate end, LocalDate today) {
        return !start.isAfter(today) && (end == null || !end.isBefore(today));
    }

    private PersonnelAssignmentView mapPersonnel(ResultSet rs, LocalDate today) throws SQLException {
        LocalDate start = rs.getObject("start_date", LocalDate.class);
        LocalDate end = rs.getObject("end_date", LocalDate.class);
        return new PersonnelAssignmentView(
                rs.getObject("id", UUID.class),
                rs.getObject("project_id", UUID.class),
                rs.getObject("personnel_id", UUID.class),
                rs.getString("full_name"),
                rs.getString("employment_type"),
                rs.getString("organization"),
                rs.getObject("position_id", UUID.class),
                json.readValue(rs.getString("position_name"), NAME),
                rs.getString("project_role"),
                rs.getBoolean("has_login"),
                rs.getObject("user_id", UUID.class),
                start,
                end,
                active(start, end, today));
    }

    private EquipmentAssignmentView mapEquipment(ResultSet rs, LocalDate today) throws SQLException {
        LocalDate start = rs.getObject("start_date", LocalDate.class);
        LocalDate end = rs.getObject("end_date", LocalDate.class);
        return new EquipmentAssignmentView(
                rs.getObject("id", UUID.class),
                rs.getObject("project_id", UUID.class),
                rs.getObject("asset_id", UUID.class),
                rs.getString("asset_code"),
                rs.getString("asset_name"),
                rs.getString("type_code"),
                json.readValue(rs.getString("type_name"), NAME),
                rs.getString("category"),
                start,
                end,
                active(start, end, today));
    }
}
