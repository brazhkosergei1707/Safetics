package com.safetics.core.auth;

import org.springframework.http.HttpStatus;

/** Помилка входу з кодом для клієнта: invalid_credentials, invalid_refresh_token, not_a_member. */
public class AuthException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public AuthException(HttpStatus status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    static AuthException invalidCredentials() {
        return new AuthException(HttpStatus.UNAUTHORIZED, "invalid_credentials");
    }

    static AuthException invalidRefreshToken() {
        return new AuthException(HttpStatus.UNAUTHORIZED, "invalid_refresh_token");
    }

    static AuthException notAMember() {
        return new AuthException(HttpStatus.FORBIDDEN, "not_a_member");
    }
}
