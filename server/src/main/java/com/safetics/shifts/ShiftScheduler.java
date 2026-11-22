package com.safetics.shifts;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

import com.safetics.core.tenancy.TenantContext;

/**
 * Планувальник змін: раз на {@code safetics.scheduler.interval} (5 хв) для кожної активної компанії
 * створює зміни, що почались, і задачі «Бурова на проєкті». Вимикається
 * {@code safetics.scheduler.enabled=false} (у тестах прохід викликається напряму).
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "safetics.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class ShiftScheduler {

    private static final Logger log = LoggerFactory.getLogger(ShiftScheduler.class);

    private final JdbcClient db;
    private final TransactionTemplate transactions;
    private final WorkShiftService shifts;
    private final Clock clock;

    public ShiftScheduler(JdbcClient db, TransactionTemplate transactions, WorkShiftService shifts, Clock clock) {
        this.db = db;
        this.transactions = transactions;
        this.shifts = shifts;
        this.clock = clock;
    }

    @Scheduled(initialDelayString = "${safetics.scheduler.initial-delay:PT30S}",
            fixedDelayString = "${safetics.scheduler.interval:PT5M}")
    public void tick() {
        List<UUID> companies = transactions.execute(status -> {
            db.sql("select set_config('app.scheduler', 'on', true)").query(String.class).single();
            return db.sql("select id from company where status = 'active'").query(UUID.class).list();
        });
        for (UUID company : companies == null ? List.<UUID>of() : companies) {
            try {
                int created = TenantContext.callAs(company, null, () -> shifts.runDue(clock.instant()));
                if (created > 0) {
                    log.info("Created {} shifts for company {}", created, company);
                }
            } catch (RuntimeException e) {
                log.warn("Shift scheduler failed for company {}", company, e);
            }
        }
    }
}
