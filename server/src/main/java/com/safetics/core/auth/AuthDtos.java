package com.safetics.core.auth;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Запити й відповіді API входу.
 *
 * <p>{@code @NotNull} у відповідях позначає поле як обов'язкове в OpenAPI, тож у згенерованому
 * TypeScript воно не буде необов'язковим. Поля без анотації можуть бути null.
 */
public final class AuthDtos {

    private AuthDtos() {
    }

    /** Телефон у міжнародному форматі (+380…); companyId — необов'язковий, якщо компаній кілька. */
    public record LoginRequest(@NotBlank String phone, @NotBlank String password, UUID companyId) {
    }

    public record RefreshRequest(@NotBlank String refreshToken) {
    }

    public record SwitchCompanyRequest(@NotNull UUID companyId) {
    }

    public record UserView(@NotNull UUID id, @NotNull String fullName, @NotNull String phone) {
    }

    /** role — код ролі користувача в цій компанії (owner, manager…), лише для показу. */
    public record CompanyView(@NotNull UUID id, @NotNull String name, String role) {
    }

    /**
     * company == null: користувач працює в кількох компаніях і ще не обрав одну (або це адмін
     * платформи без компанії). platformAdmin — може керувати компаніями платформи. position — посада
     * в поточній компанії (з запису виконавця) за мовами, якщо є.
     */
    public record SessionView(
            @NotNull UserView user,
            CompanyView company,
            @NotNull List<CompanyView> companies,
            @NotNull List<String> permissions,
            @NotNull boolean platformAdmin,
            java.util.Map<String, String> position) {
    }

    /** Для Telegram-бота і мобільного застосунку: токени в тілі відповіді, а не в cookie. */
    public record TokenResponse(
            @NotNull String accessToken,
            @NotNull Instant accessTokenExpiresAt,
            @NotNull String refreshToken,
            @NotNull Instant refreshTokenExpiresAt,
            @NotNull SessionView session) {
    }

    /** Тіло помилки: invalid_credentials, invalid_refresh_token, not_a_member. */
    public record ErrorResponse(@NotNull String error) {
    }
}
