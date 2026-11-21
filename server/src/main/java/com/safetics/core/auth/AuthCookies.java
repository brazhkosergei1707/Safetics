package com.safetics.core.auth;

import java.time.Duration;

import org.springframework.http.ResponseCookie;

/**
 * Cookie вебу. Обидві httpOnly: JavaScript до токенів доступу не має, тож XSS їх не вкраде.
 * SameSite=Lax разом з API лише на JSON захищає від CSRF без окремого токена.
 */
final class AuthCookies {

    static final String ACCESS = "sft_access";
    static final String REFRESH = "sft_refresh";

    /** Токен оновлення потрібен лише ендпоінтам входу, тож браузер не шле його з кожним запитом. */
    private static final String REFRESH_PATH = "/api/v1/auth";

    private AuthCookies() {
    }

    static ResponseCookie access(String token, Duration ttl, boolean secure) {
        return build(ACCESS, token, "/", ttl, secure);
    }

    static ResponseCookie refresh(String token, Duration ttl, boolean secure) {
        return build(REFRESH, token, REFRESH_PATH, ttl, secure);
    }

    static ResponseCookie clearAccess(boolean secure) {
        return build(ACCESS, "", "/", Duration.ZERO, secure);
    }

    static ResponseCookie clearRefresh(boolean secure) {
        return build(REFRESH, "", REFRESH_PATH, Duration.ZERO, secure);
    }

    private static ResponseCookie build(String name, String value, String path, Duration maxAge, boolean secure) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path(path)
                .maxAge(maxAge)
                .build();
    }
}
