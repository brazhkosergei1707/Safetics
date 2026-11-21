package com.safetics.core.tenancy;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Поточна компанія (tenant) і користувач для потоку, що обробляє запит.
 *
 * <p>Встановлюється на вході запиту (пізніше — фільтром, що читає JWT) і передається в PostgreSQL
 * через {@link TenantAwareDataSource}. Без встановленого контексту RLS не віддає жодного рядка
 * з таблиць компаній.
 */
public final class TenantContext {

    public record Tenant(UUID companyId, UUID userId) {
        public Tenant {
            Objects.requireNonNull(companyId, "companyId");
        }
    }

    private static final ThreadLocal<Tenant> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static Optional<Tenant> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static <T> T callAs(UUID companyId, UUID userId, Supplier<T> action) {
        Tenant previous = CURRENT.get();
        CURRENT.set(new Tenant(companyId, userId));
        try {
            return action.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    public static void runAs(UUID companyId, UUID userId, Runnable action) {
        callAs(companyId, userId, () -> {
            action.run();
            return null;
        });
    }
}
