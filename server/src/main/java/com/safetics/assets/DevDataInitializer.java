package com.safetics.assets;

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
import com.safetics.core.tenancy.TenantContext;

/**
 * Лише профіль dev: встановлює шаблон «Розвідувальне буріння» демо-компаніям і додає бурову та авто,
 * щоб у вебі було на що дивитися. Повторний запуск нічого не дублює.
 */
@Component
@Profile("dev")
class DevDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataInitializer.class);

    /** Демо-компанії з db/dev/R__dev_seed.sql */
    private static final List<UUID> DEMO_COMPANIES = List.of(
            UUID.fromString("d0000000-0000-4000-8000-000000000001"),
            UUID.fromString("d0000000-0000-4000-8000-000000000002"));

    private final JdbcClient db;
    private final TemplateService templates;
    private final AssetTypeService types;
    private final AssetService assets;

    DevDataInitializer(JdbcClient db, TemplateService templates, AssetTypeService types, AssetService assets) {
        this.db = db;
        this.templates = templates;
        this.types = types;
        this.assets = assets;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (UUID companyId : DEMO_COMPANIES) {
            TenantContext.runAs(companyId, null, () -> seed(companyId));
        }
    }

    private void seed(UUID companyId) {
        Long companies = db.sql("select count(*) from company where id = ?").param(companyId).query(Long.class).single();
        if (companies == 0) {
            return;
        }
        if (types.list(true).isEmpty()) {
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
}
