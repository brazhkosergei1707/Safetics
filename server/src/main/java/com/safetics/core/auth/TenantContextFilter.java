package com.safetics.core.auth;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import com.safetics.core.tenancy.TenantContext;

/**
 * Переносить користувача ({@code sub}) і компанію ({@code cid}) з перевіреного JWT у
 * {@link TenantContext} на час запиту. Звідти їх бере TenantAwareDataSource для RLS.
 *
 * <p>Створюється в SecurityConfig через new, а не як бін: інакше Spring Boot зареєстрував би його
 * ще й як звичайний сервлет-фільтр поза ланцюжком безпеки.
 */
final class TenantContextFilter extends OncePerRequestFilter {

    static final String COMPANY_CLAIM = "cid";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)) {
            chain.doFilter(request, response);
            return;
        }
        Jwt jwt = jwtAuthentication.getToken();
        UUID userId = UUID.fromString(jwt.getSubject());
        String companyClaim = jwt.getClaimAsString(COMPANY_CLAIM);
        UUID companyId = companyClaim == null ? null : UUID.fromString(companyClaim);

        try (TenantContext.Scope ignored = TenantContext.enter(companyId, userId)) {
            chain.doFilter(request, response);
        }
    }
}
