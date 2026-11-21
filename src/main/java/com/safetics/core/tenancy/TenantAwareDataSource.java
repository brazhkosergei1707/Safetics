package com.safetics.core.tenancy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Обгортка над пулом з'єднань: кожного разу, коли з'єднання береться з пулу, записує в сесію
 * PostgreSQL поточну компанію і користувача ({@code app.company_id}, {@code app.user_id}).
 * Політики RLS читають ці значення через функції {@code app_company_id()} і {@code app_user_id()}.
 *
 * <p>Значення перезаписуються при кожному отриманні з'єднання, тому з'єднання, повернуте в пул
 * після запиту компанії A, не може «протягнути» A в наступний запит. Без контексту пишеться
 * порожній рядок, і RLS не віддає нічого.
 *
 * <p>Обмеження:
 * <ul>
 *   <li>Розраховано на {@code autoCommit=true} у пулі (дефолт Hikari). Інакше відкат першої
 *       транзакції відкотив би й налаштування, тому для цього випадку робимо commit одразу.</li>
 *   <li>Не сумісно з PgBouncer у режимі transaction pooling (сесійні змінні). Якщо він знадобиться,
 *       перейти на {@code set_config(..., true)} на початку кожної транзакції.</li>
 * </ul>
 */
public class TenantAwareDataSource extends DelegatingDataSource {

    private static final String SET_TENANT_SQL =
            "select set_config('app.company_id', ?, false), set_config('app.user_id', ?, false)";

    public TenantAwareDataSource(DataSource target) {
        super(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return applyTenant(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return applyTenant(super.getConnection(username, password));
    }

    private Connection applyTenant(Connection connection) throws SQLException {
        Optional<TenantContext.Tenant> tenant = TenantContext.current();
        String companyId = tenant.map(TenantContext.Tenant::companyId).map(UUID::toString).orElse("");
        String userId = tenant.map(TenantContext.Tenant::userId).map(UUID::toString).orElse("");
        try (PreparedStatement statement = connection.prepareStatement(SET_TENANT_SQL)) {
            statement.setString(1, companyId);
            statement.setString(2, userId);
            statement.execute();
            if (!connection.getAutoCommit()) {
                connection.commit();
            }
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
        return connection;
    }
}
