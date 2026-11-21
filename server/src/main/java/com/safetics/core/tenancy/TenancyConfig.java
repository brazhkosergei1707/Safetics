package com.safetics.core.tenancy;

import javax.sql.DataSource;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class TenancyConfig {

    /**
     * Обгортає основний DataSource у {@link TenantAwareDataSource}, щоб усі запити застосунку
     * (JdbcClient, JdbcTemplate, транзакції) йшли з контекстом компанії.
     * Flyway має окреме з'єднання під власником схеми і цю обгортку не використовує.
     */
    @Bean
    static BeanPostProcessor tenantAwareDataSourcePostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource dataSource && !(bean instanceof TenantAwareDataSource)) {
                    return new TenantAwareDataSource(dataSource);
                }
                return bean;
            }
        };
    }
}
