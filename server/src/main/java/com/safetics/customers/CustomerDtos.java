package com.safetics.customers;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.media.Schema;

/** Запити й відповіді API замовників і контактних осіб. Поля без @NotNull можуть бути null. */
public final class CustomerDtos {

    private CustomerDtos() {
    }

    /** country — ISO 3166-1 alpha-2 (BR, MR, UA). */
    public record CustomerView(
            @NotNull UUID id,
            @NotNull String name,
            String legalCode,
            String country,
            String phone,
            String email,
            String notes,
            @NotNull int version,
            @NotNull boolean archived) {
    }

    /** version обов'язкова при зміні (оптимістичне блокування), при створенні ігнорується. */
    public record CustomerRequest(
            @NotBlank String name,
            String legalCode,
            String country,
            String phone,
            String email,
            String notes,
            Integer version) {
    }

    /** portalRole — роль входу в портал (client_rep, client_manager) або null, якщо входу немає. */
    public record ContactView(
            @NotNull UUID id,
            @NotNull UUID customerId,
            @NotNull String fullName,
            String jobTitle,
            String phone,
            String email,
            @Schema(allowableValues = {"client_rep", "client_manager"}) String portalRole,
            @NotNull int version,
            @NotNull boolean archived) {
    }

    public record ContactRequest(
            @NotBlank String fullName,
            String jobTitle,
            String phone,
            String email,
            Integer version) {
    }

    /**
     * Вхід контактної особи в портал за її телефоном. password — початковий пароль; потрібен, лише якщо
     * облікового запису з цим телефоном ще немає (інакше людина входить своїм паролем).
     */
    public record PortalAccessRequest(
            @NotBlank @Schema(allowableValues = {"client_rep", "client_manager"}) String role,
            String password) {
    }

    /** newAccount — створено новий обліковий запис; false — людина вже мала вхід у Safetics. */
    public record PortalAccessResponse(@NotNull ContactView contact, @NotNull boolean newAccount) {
    }
}
