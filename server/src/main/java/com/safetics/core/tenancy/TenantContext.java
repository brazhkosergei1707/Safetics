package com.safetics.core.tenancy;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Поточна компанія (tenant) і користувач для потоку, що обробляє запит.
 *
 * <p>На вході HTTP-запиту контекст заповнює {@code TenantContextFilter} з JWT, далі він потрапляє
 * в PostgreSQL через {@link TenantAwareDataSource}. Без контексту RLS не віддає жодного рядка
 * з таблиць компаній.
 *
 * <p>{@code companyId} може бути порожнім: користувач увійшов, але ще не обрав компанію. Тоді він
 * бачить лише власні членства і компанії, в яких працює.
 */
public final class TenantContext {

    public record Tenant(UUID companyId, UUID userId) {
    }

    /** Відновлює попередній контекст при закритті. Для try-with-resources у фільтрах. */
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    private static final ThreadLocal<Tenant> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static Optional<Tenant> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** Компанія поточного запиту; без неї операції з даними компанії неможливі. */
    public static UUID requireCompanyId() {
        return current().map(Tenant::companyId)
                .orElseThrow(() -> new IllegalStateException("No company selected in the current context"));
    }

    public static Scope enter(UUID companyId, UUID userId) {
        Tenant previous = CURRENT.get();
        CURRENT.set(new Tenant(companyId, userId));
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }

    public static <T> T callAs(UUID companyId, UUID userId, Supplier<T> action) {
        try (Scope ignored = enter(companyId, userId)) {
            return action.get();
        }
    }

    public static void runAs(UUID companyId, UUID userId, Runnable action) {
        try (Scope ignored = enter(companyId, userId)) {
            action.run();
        }
    }
}
