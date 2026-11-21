package com.safetics.core.auth;

import java.util.Set;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;

/**
 * Шукає токен доступу спершу в заголовку {@code Authorization: Bearer} (Telegram-бот, мобільний
 * застосунок), потім у cookie {@code sft_access} (веб).
 *
 * <p>На публічних ендпоінтах входу токен не читається зовсім: інакше прострочена cookie давала б
 * 401 навіть на сторінці входу.
 */
final class CookieOrHeaderBearerTokenResolver implements BearerTokenResolver {

    private final DefaultBearerTokenResolver headerResolver = new DefaultBearerTokenResolver();

    @Override
    public String resolve(HttpServletRequest request) {
        if (isPublic(request)) {
            return null;
        }
        String fromHeader = headerResolver.resolve(request);
        if (fromHeader != null) {
            return fromHeader;
        }
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (AuthCookies.ACCESS.equals(cookie.getName()) && !cookie.getValue().isBlank()) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }

    static boolean isPublic(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return SecurityConfig.PUBLIC_AUTH_PATHS.contains(path)
                || Set.of("/actuator/health", "/error", "/swagger-ui.html").contains(path)
                || path.startsWith("/v3/api-docs")
                || path.startsWith("/swagger-ui/");
    }
}
