package com.safetics.core.auth;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Запити й відповіді API входу. */
public final class AuthDtos {

    private AuthDtos() {
    }

    /** Телефон у міжнародному форматі (+380…); companyId — необов'язковий, якщо компаній кілька. */
    public record LoginRequest(String phone, String password, UUID companyId) {
    }

    public record RefreshRequest(String refreshToken) {
    }

    public record SwitchCompanyRequest(UUID companyId) {
    }

    public record UserView(UUID id, String fullName, String phone) {
    }

    /** role — код ролі користувача в цій компанії (owner, manager…), лише для показу. */
    public record CompanyView(UUID id, String name, String role) {
    }

    /** company == null: користувач працює в кількох компаніях і ще не обрав одну. */
    public record SessionView(
            UserView user,
            CompanyView company,
            List<CompanyView> companies,
            List<String> permissions) {
    }

    /** Для Telegram-бота і мобільного застосунку: токени в тілі відповіді, а не в cookie. */
    public record TokenResponse(
            String accessToken,
            Instant accessTokenExpiresAt,
            String refreshToken,
            Instant refreshTokenExpiresAt,
            SessionView session) {
    }
}
