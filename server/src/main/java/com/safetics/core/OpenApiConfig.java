package com.safetics.core;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

/**
 * Опис API для Swagger UI і генерації TypeScript-клієнта.
 * Дві схеми входу: cookie (веб) і Bearer (Telegram-бот, мобільний застосунок).
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    @Bean
    OpenAPI safeticsOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Safetics API")
                        .version("v1")
                        .description("Shift reports, stock and HSE for drilling and field-service companies."))
                .components(new Components()
                        .addSecuritySchemes("cookieAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.COOKIE)
                                .name("sft_access"))
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("cookieAuth"))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}
