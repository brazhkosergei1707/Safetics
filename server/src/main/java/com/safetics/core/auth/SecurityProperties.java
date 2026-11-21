package com.safetics.core.auth;

import java.time.Duration;
import java.util.Base64;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Налаштування входу: {@code safetics.security.*} в application.yml.
 *
 * @param jwtSecret       Base64-ключ для підпису JWT (HS256), мінімум 32 байти
 * @param accessTokenTtl  час життя токена доступу
 * @param refreshTokenTtl час життя токена оновлення
 * @param secureCookies   прапорець Secure для cookie (false лише для локального http)
 */
@ConfigurationProperties("safetics.security")
public record SecurityProperties(
        String jwtSecret,
        @DefaultValue("15m") Duration accessTokenTtl,
        @DefaultValue("30d") Duration refreshTokenTtl,
        @DefaultValue("true") boolean secureCookies) {

    public SecretKey signingKey() {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException(
                    "safetics.security.jwt-secret is not set. Run with the 'dev' profile locally "
                            + "or set SAFETICS_JWT_SECRET (Base64, at least 32 bytes).");
        }
        byte[] bytes = Base64.getDecoder().decode(jwtSecret.trim());
        if (bytes.length < 32) {
            throw new IllegalStateException("safetics.security.jwt-secret must be at least 32 bytes");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }
}
