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

    DevDataInitializer(JdbcClient db, TemplateService templates, AssetTypeService types, AssetService assets,
                       PositionService positions, PersonnelService personnel, AssignmentService assignments) {
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
    }

    private void seed(UUID companyId) {
        Long companies = db.sql("select count(*) from company where id = ?").param(companyId).query(Long.class).single();
        if (companies == 0) {
            return;
        }
        // Повторне встановлення лише додає відсутнє (посади з'явилися в шаблоні в кроці 2б)
        if (types.list(true).isEmpty() || positions.list(true).isEmpty()) {
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
