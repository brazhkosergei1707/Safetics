package com.safetics.shifts;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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
import com.safetics.projects.ProjectDtos.Shift;
import com.safetics.projects.ProjectRoles;
import com.safetics.projects.ProjectService;
import com.safetics.shifts.ShiftDtos.BoardAsset;
import com.safetics.shifts.ShiftDtos.CrewEntry;
import com.safetics.shifts.ShiftDtos.CrewMemberView;
import com.safetics.shifts.ShiftDtos.CrewUpdateRequest;
import com.safetics.shifts.ShiftDtos.ShiftBoardView;
import com.safetics.shifts.ShiftDtos.ShiftTaskRef;
import com.safetics.shifts.ShiftDtos.WorkShiftCreateRequest;
import com.safetics.shifts.ShiftDtos.WorkShiftView;
import com.safetics.tasks.TaskWorkflow;
import com.safetics.tasks.TaskWorkflow.ShiftContext;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Зміни основного обладнання і бригади.
 *
 * <p>Зміна створюється планувальником у момент свого початку (за графіком і часовим поясом проєкту)
 * для кожного основного обладнання на проєкті, або вручну. Бригада заповнюється з попередньої зміни
 * цього обладнання з тим самим кодом — лише ті, хто в цей день призначений на проєкт. Створення
 * зміни запускає правила shift_created задачі «Бурова на проєкті» (змінний рапорт → бурильник зміни).
 *
 * <p>Бригаду змінюють менеджер (project.manage) і супервайзер проєкту.
 */
@Service
public class WorkShiftService {

    private static final TypeReference<Map<String, String>> NAME = new TypeReference<>() {
    };
    private static final TypeReference<List<Shift>> SHIFTS = new TypeReference<>() {
    };
    private static final DateTimeFormatter LABEL_DATE = DateTimeFormatter.ofPattern("dd.MM");
    /** Найдовший період дошки. */
    static final int MAX_BOARD_DAYS = 62;

    private static final String SELECT = """
            select s.id, s.project_id, s.asset_id, a.code as asset_code, a.name as asset_name, s.shift_date, s.shift_code,
                   s.shift_name, s.starts_at, s.ends_at, s.duration_minutes, s.source, s.version
            from work_shift s join asset a on a.id = s.asset_id
            """;

    private record ShiftRow(UUID id, UUID projectId, UUID assetId, String assetCode, String assetName, LocalDate date,
                            String shiftCode, String shiftName, OffsetDateTime startsAt, OffsetDateTime endsAt,
                            int durationMinutes, String source, int version) {
    }

    private record ActiveProject(UUID id, ZoneId zone, LocalDate startDate, LocalDate endDate) {
    }

    private final JdbcClient db;
    private final JsonMapper json;
    private final ProjectService projects;
    private final ProjectRoles roles;
    private final RigTaskService rigTasks;
    private final TaskWorkflow tasks;
    private final AuditService audit;

    public WorkShiftService(JdbcClient db, JsonMapper json, ProjectService projects, ProjectRoles roles,
                            RigTaskService rigTasks, TaskWorkflow tasks, AuditService audit) {
        this.db = db;
        this.json = json;
        this.projects = projects;
        this.roles = roles;
        this.rigTasks = rigTasks;
        this.tasks = tasks;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- читання

    /** Дошка «обладнання × зміни» за період (включно), за замовчуванням — останні 7 днів. */
    public ShiftBoardView board(UUID projectId, LocalDate from, LocalDate to) {
        ProjectView project = projects.get(projectId);
        LocalDate end = to != null ? to : projects.today(project);
        LocalDate start = from != null ? from : end.minusDays(6);
        if (start.isAfter(end)) {
            throw ApiException.validation("from", "after_to");
        }
        if (start.plusDays(MAX_BOARD_DAYS).isBefore(end)) {
            throw ApiException.validation("to", "too_large");
        }
        List<BoardAsset> assets = db.sql("""
                        select ea.asset_id, a.code, a.name, ea.start_date, ea.end_date,
                               (select max(case st.attributes ->> 'criticality' when 'high' then 3 when 'low' then 1 else 2 end)
                                from task st join task_type stt on stt.id = st.task_type_id
                                where st.asset_id = a.id and stt.code = 'service'
                                  and st.status_category in ('new', 'active')) as service_level
                        from equipment_assignment ea join asset a on a.id = ea.asset_id
                        where ea.project_id = ? and ea.exclusive
                          and ea.start_date <= ? and (ea.end_date is null or ea.end_date >= ?)
                        order by a.code, ea.start_date
                        """)
                .params(projectId, end, start)
                .query((rs, n) -> new BoardAsset(rs.getObject("asset_id", UUID.class), rs.getString("code"),
                        rs.getString("name"), rs.getObject("start_date", LocalDate.class),
                        rs.getObject("end_date", LocalDate.class),
                        switch (rs.getObject("service_level") == null ? 0 : rs.getInt("service_level")) {
                            case 3 -> "high";
                            case 2 -> "medium";
                            case 1 -> "low";
                            default -> "ok";
                        }))
                .list();
        List<WorkShiftView> shifts = views(db.sql(SELECT + """
                         where s.project_id = ? and s.shift_date between ? and ?
                         order by s.shift_date, s.starts_at, a.code
                        """)
                .params(projectId, start, end).query(WorkShiftService::row).list());
        return new ShiftBoardView(assets, scheduleOn(projectId, end), shifts);
    }

    public WorkShiftView get(UUID id) {
        return find(id).orElseThrow(ApiException::notFound);
    }

    public Optional<WorkShiftView> find(UUID id) {
        return db.sql(SELECT + " where s.id = ?").param(id).query(WorkShiftService::row).optional()
                .map(r -> views(List.of(r)).getFirst());
    }

    // ---------------------------------------------------------------- зміни

    /** Зміна вручну: позапланова або пропущена. Час — з графіка, що діє на дату. */
    @Transactional
    public WorkShiftView createManual(UUID projectId, WorkShiftCreateRequest request) {
        ProjectView project = openProject(projectId);
        requireEditor(project);
        if (request.date().isBefore(project.startDate()) || (project.endDate() != null && request.date().isAfter(project.endDate()))) {
            throw ApiException.validation("date", "outside_project");
        }
        Boolean assigned = db.sql("""
                        select exists (select 1 from equipment_assignment
                                       where project_id = ? and asset_id = ? and exclusive
                                         and start_date <= ? and (end_date is null or end_date >= ?))
                        """)
                .params(projectId, request.assetId(), request.date(), request.date()).query(Boolean.class).single();
        if (!Boolean.TRUE.equals(assigned)) {
            throw ApiException.validation("assetId", "not_on_project");
        }
        Shift shift = scheduleOn(projectId, request.date()).stream().filter(s -> s.code().equals(request.shiftCode()))
                .findFirst().orElseThrow(() -> ApiException.validation("shiftCode", "not_found"));
        List<CrewEntry> crew = request.crew();
        List<UUID> positions = crew == null || crew.isEmpty() ? null
                : validateCrew(projectId, request.date(), shift.durationMinutes(), crew);
        UUID id = create(project, request.assetId(), request.date(), shift, "manual",
                positions == null ? null : new CrewInput(crew, positions));
        if (id == null) {
            throw ApiException.conflict("shift_exists");
        }
        return get(id);
    }

    /**
     * Зміни за період за графіком, що діяв у кожен день (для внесення минулих даних): усе основне
     * обладнання або одне, наявні зміни пропускаються, задачі зміни створюються. До 62 днів, не
     * пізніше сьогодні, у межах дат проєкту.
     */
    @Transactional
    public int generate(UUID projectId, LocalDate from, LocalDate to, UUID assetId) {
        ProjectView project = openProject(projectId);
        requireEditor(project);
        LocalDate today = projects.today(project);
        if (from.isAfter(to)) {
            throw ApiException.validation("from", "after_to");
        }
        if (to.isAfter(today)) {
            throw ApiException.validation("to", "in_future");
        }
        if (from.plusDays(MAX_BOARD_DAYS).isBefore(to)) {
            throw ApiException.validation("to", "too_large");
        }
        LocalDate start = from.isBefore(project.startDate()) ? project.startDate() : from;
        LocalDate end = project.endDate() != null && project.endDate().isBefore(to) ? project.endDate() : to;
        int created = 0;
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            List<UUID> assets = db.sql("""
                            select asset_id from equipment_assignment
                            where project_id = ? and exclusive and start_date <= ? and (end_date is null or end_date >= ?)
                            """)
                    .params(projectId, date, date).query(UUID.class).list();
            for (Shift shift : scheduleOn(projectId, date)) {
                for (UUID asset : assets) {
                    if ((assetId == null || assetId.equals(asset)) && create(project, asset, date, shift, "manual") != null) {
                        created++;
                    }
                }
            }
        }
        audit.record("project", projectId, "generate_shifts", Map.of("from", from.toString(), "to", to.toString(),
                "created", created));
        return created;
    }

    /** Бригада зміни повністю замінюється. Бурильник зміни — один; змінився — задачі зміни переходять до нього. */
    @Transactional
    public WorkShiftView updateCrew(UUID id, CrewUpdateRequest request) {
        WorkShiftView shift = get(id);
        ProjectView project = openProject(shift.projectId());
        if (Boolean.TRUE.equals(db.sql("select exists (select 1 from project_month_close where project_id = ? and month = ?)")
                .params(shift.projectId(), shift.date().withDayOfMonth(1)).query(Boolean.class).single())) {
            throw ApiException.conflict("month_closed");
        }
        requireEditor(project);
        if (request.version() != shift.version()) {
            throw ApiException.conflict("stale_version");
        }
        Boolean submitted = db.sql("select exists (select 1 from shift_report where shift_id = ? and status in ('submitted', 'accepted'))")
                .param(id).query(Boolean.class).single();
        if (Boolean.TRUE.equals(submitted)) {
            throw ApiException.conflict("report_submitted");
        }
        List<CrewEntry> entries = request.crew();
        List<UUID> positions = validateCrew(shift.projectId(), shift.date(), shift.durationMinutes(), entries);
        UUID previousLead = leadUser(id);
        db.sql("delete from work_shift_crew where shift_id = ?").param(id).update();
        insertCrewEntries(id, entries, positions, shift.durationMinutes());
        db.sql("update work_shift set version = version + 1, updated_at = now() where id = ?").param(id).update();
        tasks.reassignShiftTasks(id, previousLead, leadUser(id));
        audit.record("work_shift", id, "crew", Map.of("count", entries.size()));
        return get(id);
    }

    /** Перевіряє бригаду (люди на проєкті на дату, один відповідальний з входом); повертає посади рядків. */
    private List<UUID> validateCrew(UUID projectId, LocalDate date, int durationMinutes, List<CrewEntry> entries) {
        Map<String, String> errors = new LinkedHashMap<>();
        Set<UUID> seen = new HashSet<>();
        List<UUID> positions = new ArrayList<>();
        // Відповідальний виконавець — рівно один, якщо бригада не порожня, і з входом (на нього задачі зміни)
        long leads = entries.stream().filter(e -> Boolean.TRUE.equals(e.lead())).count();
        if (leads > 1) {
            errors.put("crew", "one_lead");
        } else if (leads == 0 && !entries.isEmpty()) {
            errors.put("crew", "lead_required");
        }
        for (int i = 0; i < entries.size(); i++) {
            CrewEntry entry = entries.get(i);
            String prefix = "crew[" + i + "].";
            if (!seen.add(entry.personnelId())) {
                errors.put(prefix + "personnelId", "duplicate");
            }
            Optional<UUID> assignedPosition = assignedPosition(projectId, entry.personnelId(), date);
            if (assignedPosition.isEmpty()) {
                errors.put(prefix + "personnelId", "not_on_project");
            }
            UUID position = entry.positionId() != null ? entry.positionId() : assignedPosition.orElse(null);
            if (entry.positionId() != null) {
                Long found = db.sql("select count(*) from position where id = ?").param(entry.positionId())
                        .query(Long.class).single();
                if (found == 0) {
                    errors.put(prefix + "positionId", "not_found");
                }
            }
            positions.add(position);
            if (Boolean.TRUE.equals(entry.lead()) && !hasLogin(entry.personnelId())) {
                errors.put(prefix + "lead", "no_login");
            }
            if (entry.minutes() != null && (entry.minutes() < 0 || entry.minutes() > durationMinutes)) {
                errors.put(prefix + "minutes", "too_large");
            }
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        return positions;
    }

    private boolean hasLogin(UUID personnelId) {
        return Boolean.TRUE.equals(db.sql("""
                        select exists (select 1 from personnel p
                                                join membership m on m.company_id = p.company_id and m.user_id = p.user_id
                                       where p.id = ? and m.status = 'active')
                        """).param(personnelId).query(Boolean.class).single());
    }

    private void insertCrewEntries(UUID shiftId, List<CrewEntry> entries, List<UUID> positions, int durationMinutes) {
        for (int i = 0; i < entries.size(); i++) {
            CrewEntry entry = entries.get(i);
            insertCrew(shiftId, entry.personnelId(), positions.get(i), Boolean.TRUE.equals(entry.lead()),
                    entry.minutes() != null ? entry.minutes() : durationMinutes, i);
        }
    }

    // ---------------------------------------------------------------- система

    /**
     * Створює зміну, якщо її ще немає (повторний виклик нічого не робить): бригада з попередньої
     * зміни, правила shift_created задачі обладнання. Повертає id нової зміни або null.
     */
    @Transactional
    public UUID create(ProjectView project, UUID assetId, LocalDate date, Shift shift, String source) {
        return create(project, assetId, date, shift, source, null);
    }

    /** Бригада, вказана при створенні зміни вручну (уже перевірена). */
    private record CrewInput(List<CrewEntry> entries, List<UUID> positions) {
    }

    private UUID create(ProjectView project, UUID assetId, LocalDate date, Shift shift, String source, CrewInput crew) {
        ZoneId zone = ZoneId.of(project.timeZone());
        OffsetDateTime startsAt = date.atTime(LocalTime.parse(shift.start())).atZone(zone).toOffsetDateTime();
        OffsetDateTime endsAt = startsAt.plusMinutes(shift.durationMinutes());
        UUID id = db.sql("""
                        insert into work_shift (company_id, project_id, asset_id, shift_date, shift_code, shift_name,
                                                starts_at, ends_at, duration_minutes, source, created_by)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        on conflict (asset_id, shift_date, shift_code) do nothing
                        returning id
                        """)
                .params(TenantContext.requireCompanyId(), project.id(), assetId, date, shift.code(), shift.name(),
                        startsAt, endsAt, shift.durationMinutes(), source, currentUserId())
                .query(UUID.class).optional().orElse(null);
        if (id == null) {
            return null;
        }
        if (crew != null) {
            insertCrewEntries(id, crew.entries(), crew.positions(), shift.durationMinutes());
        } else {
            prefillCrew(id, project.id(), assetId, date, shift);
        }
        audit.record("work_shift", id, "create", Map.of("projectId", project.id(), "date", date.toString(),
                "shift", shift.code(), "source", source));
        String label = date.format(LABEL_DATE) + " " + shift.name();
        rigTasks.taskFor(project.id(), assetId, date)
                .ifPresent(taskId -> tasks.runShiftCreated(taskId, new ShiftContext(id, leadUser(id), label)));
        return id;
    }

    /**
     * Один прохід планувальника для поточної компанії (контекст компанії вже встановлено):
     * задачі «Бурова на проєкті» для призначень, що почались / закінчились, і зміни, що йдуть зараз.
     * Повертає кількість нових змін.
     */
    @Transactional
    public int runDue(Instant now) {
        db.sql("select set_config('app.system', 'on', true)").query(String.class).single();
        List<ActiveProject> active = db.sql("select id, time_zone, start_date, end_date from project where status = 'active'")
                .query((rs, n) -> new ActiveProject(rs.getObject("id", UUID.class), ZoneId.of(rs.getString("time_zone")),
                        rs.getObject("start_date", LocalDate.class), rs.getObject("end_date", LocalDate.class)))
                .list();
        int created = 0;
        for (ActiveProject p : active) {
            LocalDate today = LocalDate.ofInstant(now, p.zone());
            for (UUID assignment : db.sql("""
                            select id from equipment_assignment
                            where project_id = ? and exclusive and task_id is null
                              and start_date <= ? and (end_date is null or end_date >= ?)
                            """)
                    .params(p.id(), today, today).query(UUID.class).list()) {
                rigTasks.ensureFor(assignment);
            }
            for (UUID assignment : db.sql("""
                            select ea.id from equipment_assignment ea join task t on t.id = ea.task_id
                            where ea.project_id = ? and ea.end_date < ? and t.status_category in ('new', 'active')
                            """)
                    .params(p.id(), today).query(UUID.class).list()) {
                rigTasks.syncFor(assignment);
            }
            ProjectView project = null;
            // Зміна через північ належить даті свого початку — тому і вчора
            for (LocalDate date : List.of(today.minusDays(1), today)) {
                if (date.isBefore(p.startDate()) || (p.endDate() != null && date.isAfter(p.endDate()))) {
                    continue;
                }
                for (Shift shift : scheduleOn(p.id(), date)) {
                    Instant start = date.atTime(LocalTime.parse(shift.start())).atZone(p.zone()).toInstant();
                    Instant end = start.plusSeconds(shift.durationMinutes() * 60L);
                    if (start.isAfter(now) || !end.isAfter(now)) {
                        continue;
                    }
                    List<UUID> assets = db.sql("""
                                    select asset_id from equipment_assignment
                                    where project_id = ? and exclusive
                                      and start_date <= ? and (end_date is null or end_date >= ?)
                                    """)
                            .params(p.id(), date, date).query(UUID.class).list();
                    for (UUID asset : assets) {
                        if (project == null) {
                            project = projects.get(p.id());
                        }
                        if (create(project, asset, date, shift, "schedule") != null) {
                            created++;
                        }
                    }
                }
            }
        }
        return created;
    }

    // ---------------------------------------------------------------- допоміжне

    /** Зміни графіка, що діє на дату (остання версія з valid_from ≤ дата, інакше перша). */
    List<Shift> scheduleOn(UUID projectId, LocalDate date) {
        return db.sql("""
                        select shifts from shift_schedule
                        where project_id = ?
                        order by valid_from <= ? desc, case when valid_from <= ? then valid_from end desc nulls last,
                                 valid_from
                        limit 1
                        """)
                .params(projectId, date, date).query(String.class).optional()
                .map(value -> json.readValue(value, SHIFTS)).orElse(List.of());
    }

    private void prefillCrew(UUID shiftId, UUID projectId, UUID assetId, LocalDate date, Shift shift) {
        List<Object[]> previous = db.sql("""
                        select c.personnel_id, c.position_id, c.is_lead
                        from work_shift_crew c join work_shift s on s.id = c.shift_id
                        where s.id = (select p.id from work_shift p
                                      where p.project_id = ? and p.asset_id = ? and p.shift_code = ? and p.shift_date < ?
                                      order by p.shift_date desc limit 1)
                        order by c.sort_order
                        """)
                .params(projectId, assetId, shift.code(), date)
                .query((rs, n) -> new Object[] {rs.getObject("personnel_id", UUID.class), rs.getObject("position_id", UUID.class),
                        rs.getBoolean("is_lead")})
                .list();
        int order = 0;
        for (Object[] member : previous) {
            UUID personnelId = (UUID) member[0];
            if (assignedPosition(projectId, personnelId, date).isEmpty()) {
                continue;
            }
            insertCrew(shiftId, personnelId, (UUID) member[1], (Boolean) member[2], shift.durationMinutes(), order++);
        }
    }

    private void insertCrew(UUID shiftId, UUID personnelId, UUID positionId, boolean lead, int minutes, int order) {
        db.sql("""
                        insert into work_shift_crew (company_id, shift_id, personnel_id, position_id, is_lead, minutes, sort_order)
                        values (?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(TenantContext.requireCompanyId(), shiftId, personnelId, positionId, lead, minutes, order).update();
    }

    /** Посада людини в призначенні на проєкт, що діє на дату; порожньо — не на проєкті. */
    private Optional<UUID> assignedPosition(UUID projectId, UUID personnelId, LocalDate date) {
        return db.sql("""
                        select position_id from personnel_assignment
                        where project_id = ? and personnel_id = ? and start_date <= ? and (end_date is null or end_date >= ?)
                        order by start_date desc limit 1
                        """)
                .params(projectId, personnelId, date, date).query(UUID.class).optional();
    }

    /** Обліковий запис бурильника зміни (людина з входом, активне членство). */
    private UUID leadUser(UUID shiftId) {
        return db.sql("""
                        select p.user_id
                        from work_shift_crew c
                                 join personnel p on p.id = c.personnel_id
                                 join membership m on m.company_id = p.company_id and m.user_id = p.user_id
                        where c.shift_id = ? and c.is_lead and m.status = 'active'
                        """)
                .param(shiftId).query(UUID.class).optional().orElse(null);
    }

    private List<WorkShiftView> views(List<ShiftRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<CrewMemberView>> crew = new LinkedHashMap<>();
        Map<UUID, List<ShiftTaskRef>> shiftTasks = new LinkedHashMap<>();
        List<UUID> ids = rows.stream().map(ShiftRow::id).toList();
        db.sql("""
                        select c.shift_id, c.personnel_id, p.full_name, c.position_id, pos.name as position_name, c.is_lead,
                               c.minutes, p.user_id
                        from work_shift_crew c
                                 join personnel p on p.id = c.personnel_id
                                 join position pos on pos.id = c.position_id
                        where c.shift_id in (:ids)
                        order by c.sort_order, p.full_name
                        """)
                .param("ids", ids)
                .query((rs, n) -> {
                    crew.computeIfAbsent(rs.getObject("shift_id", UUID.class), k -> new ArrayList<>())
                            .add(new CrewMemberView(rs.getObject("personnel_id", UUID.class), rs.getString("full_name"),
                                    rs.getObject("position_id", UUID.class), json.readValue(rs.getString("position_name"), NAME),
                                    rs.getBoolean("is_lead"), rs.getInt("minutes"), rs.getObject("user_id", UUID.class)));
                    return null;
                })
                .list();
        db.sql("""
                        select t.work_shift_id, t.id, t.number, t.title, tt.code as type_code, t.status, t.status_category
                        from task t join task_type tt on tt.id = t.task_type_id
                        where t.work_shift_id in (:ids)
                        order by t.number
                        """)
                .param("ids", ids)
                .query((rs, n) -> {
                    shiftTasks.computeIfAbsent(rs.getObject("work_shift_id", UUID.class), k -> new ArrayList<>())
                            .add(new ShiftTaskRef(rs.getObject("id", UUID.class), rs.getLong("number"), rs.getString("title"),
                                    rs.getString("type_code"), rs.getString("status"), rs.getString("status_category")));
                    return null;
                })
                .list();
        OffsetDateTime now = OffsetDateTime.now();
        return rows.stream().map(r -> new WorkShiftView(r.id(), r.projectId(), r.assetId(), r.assetCode(), r.assetName(),
                r.date(), r.shiftCode(), r.shiftName(), r.startsAt(), r.endsAt(), r.durationMinutes(),
                now.isBefore(r.startsAt()) ? "upcoming" : now.isBefore(r.endsAt()) ? "running" : "finished",
                r.source(), crew.getOrDefault(r.id(), List.of()), shiftTasks.getOrDefault(r.id(), List.of()), r.version()))
                .toList();
    }

    private void requireEditor(ProjectView project) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean manage = false;
        if (authentication != null) {
            for (GrantedAuthority authority : authentication.getAuthorities()) {
                manage |= "project.manage".equals(authority.getAuthority());
            }
        }
        if (!manage && !roles.isSupervisor(project)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", Map.of());
        }
    }

    private ProjectView openProject(UUID projectId) {
        ProjectView project = projects.get(projectId);
        if ("closed".equals(project.status())) {
            throw ApiException.conflict("project_closed");
        }
        return project;
    }

    private static UUID currentUserId() {
        return TenantContext.current().map(TenantContext.Tenant::userId).orElse(null);
    }

    private static ShiftRow row(ResultSet rs, int n) throws SQLException {
        return new ShiftRow(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getObject("asset_id", UUID.class), rs.getString("asset_code"), rs.getString("asset_name"),
                rs.getObject("shift_date", LocalDate.class), rs.getString("shift_code"), rs.getString("shift_name"),
                rs.getObject("starts_at", OffsetDateTime.class), rs.getObject("ends_at", OffsetDateTime.class),
                rs.getInt("duration_minutes"), rs.getString("source"), rs.getInt("version"));
    }
}
