package com.safetics.platform;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.media.Schema;

/** Запити й відповіді API платформи (лише адмін платформи). */
public final class PlatformDtos {

    private PlatformDtos() {
    }

    public record CompanyAdminView(@NotNull UUID userId, @NotNull String fullName, @NotNull String phone) {
    }

    /** members — кількість активних учасників. */
    public record PlatformCompanyView(
            @NotNull UUID id,
            @NotNull String name,
            String legalCode,
            @NotNull @Schema(allowableValues = {"active", "suspended", "archived"}) String status,
            String templateKey,
            @NotNull OffsetDateTime createdAt,
            @NotNull List<CompanyAdminView> admins,
            @NotNull int members) {
    }

    /**
     * Нова компанія з першим адміном. adminPassword — лише якщо облікового запису з цим телефоном ще
     * немає (інакше адмін входить своїм паролем).
     */
    public record CreateCompanyRequest(
            @NotBlank String name,
            String legalCode,
            @NotBlank String templateKey,
            @NotBlank String adminFullName,
            @NotBlank String adminPhone,
            String adminPassword) {
    }

    /** newAdminAccount — для адміна створено новий обліковий запис (передайте йому пароль). */
    public record CreateCompanyResponse(@NotNull PlatformCompanyView company, @NotNull boolean newAdminAccount) {
    }
}
