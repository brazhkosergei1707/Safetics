package com.safetics.assets;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.safetics.assets.AssetDtos.AssetCreateRequest;
import com.safetics.assets.AssetDtos.AssetTypeView;
import com.safetics.assets.AssetDtos.AssetView;
import com.safetics.core.tenancy.TenantContext;
import com.safetics.personnel.PersonnelDtos.PersonnelRequest;
import com.safetics.personnel.PersonnelDtos.PersonnelView;
import com.safetics.personnel.PersonnelService;
import com.safetics.personnel.PositionService;
import com.safetics.projects.AssignmentDtos.EquipmentAssignmentRequest;
import com.safetics.projects.AssignmentDtos.PersonnelAssignmentRequest;
import com.safetics.projects.AssignmentService;
import com.safetics.services.ServiceTypeService;
import com.safetics.tasks.TaskTypeService;

import tools.jackson.databind.json.JsonMapper;

/**
 * Лише профіль dev: встановлює шаблон «Розвідувальне буріння» демо-компаніям, додає бурову та авто,
 * а в «Demo Drilling» — виконавців і призначення на проєкт VALE-CRJ, щоб у вебі було на що дивитися.
 * Повторний запуск нічого не дублює.
 */
@Component
@Profile("dev")
class DevDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataInitializer.class);

    /** Демо-компанії з db/dev/R__dev_seed.sql */
    private static final List<UUID> DEMO_COMPANIES = List.of(
            UUID.fromString("d0000000-0000-4000-8000-000000000001"),
            UUID.fromString("d0000000-0000-4000-8000-000000000002"));

    private static final UUID DEMO_DRILLING = DEMO_COMPANIES.getFirst();
    private static final UUID DEMO_ADMIN = UUID.fromString("d0000000-0000-4000-8000-0000000000a1");
    private static final UUID DEMO_SUPERVISOR_USER = UUID.fromString("d0000000-0000-4000-8000-0000000000a4");
    private static final UUID VALE_PROJECT = UUID.fromString("d0000000-0000-4000-8000-0000000000e1");

    private final JdbcClient db;
    private final TemplateService templates;
    private final AssetTypeService types;
    private final AssetService assets;
    private final PositionService positions;
    private final PersonnelService personnel;
    private final AssignmentService assignments;
    private final ServiceTypeService serviceTypes;
    private final TaskTypeService taskTypes;

    DevDataInitializer(JdbcClient db, TemplateService templates, AssetTypeService types, AssetService assets,
                       PositionService positions, PersonnelService personnel, AssignmentService assignments,
                       ServiceTypeService serviceTypes, TaskTypeService taskTypes) {
        this.serviceTypes = serviceTypes;
        this.taskTypes = taskTypes;
        this.db = db;
        this.templates = templates;
        this.types = types;
        this.assets = assets;
        this.positions = positions;
        this.personnel = personnel;
        this.assignments = assignments;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (UUID companyId : DEMO_COMPANIES) {
            TenantContext.runAs(companyId, null, () -> seed(companyId));
        }
        // Від імені адміна: він бачить усі проєкти (RLS)
        TenantContext.runAs(DEMO_DRILLING, DEMO_ADMIN, this::seedCrew);
        TenantContext.runAs(DEMO_DRILLING, DEMO_ADMIN, this::seedServices);
        TenantContext.runAs(DEMO_DRILLING, DEMO_ADMIN, this::seedTasks);
    }

    private void seed(UUID companyId) {
        Long companies = db.sql("select count(*) from company where id = ?").param(companyId).query(Long.class).single();
        if (companies == 0) {
            return;
        }
        // Повторне встановлення лише додає відсутнє (посади з'явилися в шаблоні в кроці 2б)
        if (types.list(true).isEmpty() || positions.list(true).isEmpty() || serviceTypes.list(true).isEmpty()
                || taskTypes.list(true).isEmpty()) {
            templates.install("exploration-drilling");
            log.info("Installed exploration-drilling template for demo company {}", companyId);
        }
        if (assets.list(new AssetService.Filter(null, null, null, null, true, 1, 0)).total() > 0) {
            return;
        }
        AssetTypeView rig = types.findByCode("drill_rig").orElseThrow();
        AssetTypeView vehicle = types.findByCode("vehicle").orElseThrow();

        assets.create(new AssetCreateRequest(rig.id(), null, "RIG-01", "Boart Longyear LF90", "active",
                Map.of("model", "LF90", "manufacturer", "Boart Longyear", "year", 2018, "engineHours", 12450)));
        assets.create(new AssetCreateRequest(vehicle.id(), null, "TRK-07", "Toyota Hilux", "active",
                Map.of("plate", "AA1234BC", "model", "Hilux", "odometer", 148200)));
        log.info("Created demo assets for company {}", companyId);
    }

    private void seedCrew() {
        Long projects = db.sql("select count(*) from project where id = ?").param(VALE_PROJECT).query(Long.class).single();
        if (projects == 0 || !personnel.list(new PersonnelService.Filter(null, null, true)).isEmpty()) {
            return;
        }
        LocalDate start = LocalDate.of(2026, 10, 1);

        PersonnelView supervisor = person("Pedro Santos", "+380500000004", "staff", null, "supervisor");
        db.sql("update personnel set user_id = ? where id = ?").params(DEMO_SUPERVISOR_USER, supervisor.id()).update();
        assign(supervisor, start);
        assign(person("Marcos Oliveira", null, "staff", null, "driller"), start);
        assign(person("Lucas Rocha", null, "staff", null, "driller_helper"), start);
        assign(person("Rafael Costa", null, "hired", "Transportes Pará", "driver"), start);
        person("Thiago Alves", null, "staff", null, "driller");

        for (String code : List.of("RIG-01", "TRK-07")) {
            AssetView asset = assets.list(new AssetService.Filter(null, null, null, code, false, 1, 0)).items().getFirst();
            assignments.assignEquipment(VALE_PROJECT, new EquipmentAssignmentRequest(asset.id(), start, null));
        }
        log.info("Created demo crew and assignments for project VALE-CRJ");
    }

    /**
     * Свердловини на VALE-CRJ у різних статусах, кожна з головною задачею (Pedro). Напряму SQL, бо в dev
     * немає користувача з токеном для перевірки прав.
     */
    private void seedServices() {
        Long existing = db.sql("select count(*) from service where project_id = ?").param(VALE_PROJECT).query(Long.class).single();
        Long project = db.sql("select count(*) from project where id = ?").param(VALE_PROJECT).query(Long.class).single();
        if (existing > 0 || project == 0) {
            return;
        }
        var holeType = serviceTypes.findByCode("drill_hole").orElseThrow();
        Object[][] holes = {
                {"BH-101", 350, 300.5, "HQ", "completed"},
                {"BH-102", 400, 182.0, "HQ", "in_progress"},
                {"BH-103", 420, null, "NQ", "planned"},
                {"BH-104", 380, null, "NQ", "planned"},
        };
        for (Object[] hole : holes) {
            Map<String, Object> attributes = new java.util.HashMap<>();
            attributes.put("plannedDepth", hole[1]);
            attributes.put("azimuth", 90);
            attributes.put("dip", -60);
            attributes.put("coreSize", hole[3]);
            if (hole[2] != null) {
                attributes.put("actualDepth", hole[2]);
            }
            UUID id = db.sql("""
                            insert into service (company_id, project_id, service_type_id, code, status, attributes,
                                                 planned_start, actual_start, actual_end)
                            values (?, ?, ?, ?, ?, cast(? as jsonb), ?, ?, ?)
                            returning id
                            """)
                    .params(DEMO_DRILLING, VALE_PROJECT, holeType.id(), hole[0], hole[4],
                            JsonMapper.builder().build().writeValueAsString(attributes),
                            LocalDate.of(2026, 10, 1),
                            "planned".equals(hole[4]) ? null : java.time.OffsetDateTime.parse("2026-10-05T07:00:00-03:00"),
                            "completed".equals(hole[4]) ? java.time.OffsetDateTime.parse("2026-10-20T19:00:00-03:00") : null)
                    .query(UUID.class).single();
            String category = switch ((String) hole[4]) {
                case "completed" -> "done";
                case "in_progress" -> "active";
                default -> "new";
            };
            String status = switch (category) {
                case "done" -> "done";
                case "active" -> "in_progress";
                default -> "new";
            };
            String typeName = taskTypes.get(holeType.taskTypeId()).name().getOrDefault("uk", "Буріння");
            UUID task = demoTask(holeType.taskTypeId(), typeName + ": " + hole[0], status, category, DEMO_SUPERVISOR_USER, id,
                    null, "{}");
            db.sql("update service set main_task_id = ? where id = ?").params(task, id).update();
        }
        log.info("Created demo drill holes for project VALE-CRJ");
    }

    /** Задачі на VALE-CRJ: буріння BH-103 блокується бурінням BH-102; сервіс RIG-01. */
    private void seedTasks() {
        Long existing = db.sql("select count(*) from task where project_id = ? and service_id is null")
                .param(VALE_PROJECT).query(Long.class).single();
        Long services = db.sql("select count(*) from service where project_id = ?").param(VALE_PROJECT).query(Long.class).single();
        if (existing > 0 || services == 0) {
            return;
        }
        UUID service = taskTypes.findByCode("service").orElseThrow().id();
        UUID rig = db.sql("select id from asset where code = 'RIG-01'").query(UUID.class).single();
        demoTask(service, "RIG-01: замінити шланг високого тиску", "new", "new", DEMO_SUPERVISOR_USER, null, rig,
                "{\"criticality\": \"medium\"}");
        List<UUID> drilling = db.sql("""
                        select s.main_task_id from service s
                        where s.project_id = ? and s.code in ('BH-102', 'BH-103') and s.main_task_id is not null
                        order by s.code
                        """)
                .param(VALE_PROJECT).query(UUID.class).list();
        if (drilling.size() == 2) {
            db.sql("insert into task_link (company_id, from_task, to_task, kind) values (?, ?, ?, 'blocks') on conflict do nothing")
                    .params(DEMO_DRILLING, drilling.get(0), drilling.get(1)).update();
        }
        log.info("Created demo tasks for project VALE-CRJ");
    }

    private UUID demoTask(UUID type, String title, String status, String category, UUID assignee, UUID serviceId,
                          UUID assetId, String attributes) {
        long number = db.sql("""
                        insert into task_counter (company_id, last_number) values (?, 1)
                        on conflict (company_id) do update set last_number = task_counter.last_number + 1
                        returning last_number
                        """)
                .param(DEMO_DRILLING).query(Long.class).single();
        return db.sql("""
                        insert into task (company_id, project_id, task_type_id, number, title, status, status_category,
                                          assignee_id, author_id, service_id, asset_id, attributes, planned_start,
                                          actual_start, actual_end)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?, ?)
                        returning id
                        """)
                .params(DEMO_DRILLING, VALE_PROJECT, type, number, title, status, category, assignee, DEMO_ADMIN,
                        serviceId, assetId, attributes, serviceId == null ? null : LocalDate.of(2026, 10, 1),
                        "new".equals(category) ? null : java.time.OffsetDateTime.parse("2026-10-05T07:00:00-03:00"),
                        "done".equals(category) ? java.time.OffsetDateTime.parse("2026-10-20T19:00:00-03:00") : null)
                .query(UUID.class).single();
    }

    private PersonnelView person(String fullName, String phone, String employmentType, String organization,
                                 String positionCode) {
        UUID positionId = positions.findByCode(positionCode).orElseThrow().id();
        return personnel.create(new PersonnelRequest(fullName, phone, employmentType, organization, positionId, null, null));
    }

    private void assign(PersonnelView person, LocalDate start) {
        // Свіжий запис: у supervisor щойно з'явився user_id (вхід) — тоді він отримає і доступ до проєкту
        PersonnelView fresh = personnel.get(person.id());
        assignments.assignPersonnel(VALE_PROJECT, new PersonnelAssignmentRequest(fresh.id(), null, start, null));
    }
}
