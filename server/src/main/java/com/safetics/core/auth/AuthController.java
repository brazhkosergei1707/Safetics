package com.safetics.core.auth;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.safetics.core.auth.AuthDtos.LoginRequest;
import com.safetics.core.auth.AuthDtos.RefreshRequest;
import com.safetics.core.auth.AuthDtos.SessionView;
import com.safetics.core.auth.AuthDtos.SwitchCompanyRequest;
import com.safetics.core.auth.AuthDtos.TokenResponse;
import com.safetics.core.auth.AuthService.LoginResult;

/**
 * Вхід. Два варіанти для різних клієнтів:
 * <ul>
 *   <li>веб — {@code /login}, {@code /refresh}, {@code /switch-company}, {@code /logout}: токени лише
 *       в httpOnly-cookie, у тілі — дані сесії;</li>
 *   <li>Telegram-бот і мобільний застосунок — {@code /token}, {@code /token/refresh}: токени в тілі,
 *       далі заголовок {@code Authorization: Bearer}.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService auth;
    private final SecurityProperties properties;

    public AuthController(AuthService auth, SecurityProperties properties) {
        this.auth = auth;
        this.properties = properties;
    }

    // ---------------------------------------------------------------- веб (cookie)

    @PostMapping("/login")
    public ResponseEntity<SessionView> login(@RequestBody LoginRequest request) {
        return withCookies(auth.login(request.phone(), request.password(), request.companyId()));
    }

    @PostMapping("/refresh")
    public ResponseEntity<SessionView> refresh(
            @CookieValue(name = AuthCookies.REFRESH, required = false) String refreshToken) {
        return withCookies(auth.refresh(refreshToken));
    }

    @PostMapping("/switch-company")
    public ResponseEntity<SessionView> switchCompany(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody SwitchCompanyRequest request,
            @CookieValue(name = AuthCookies.REFRESH, required = false) String refreshToken) {
        UUID userId = UUID.fromString(jwt.getSubject());
        return withCookies(auth.switchCompany(userId, request.companyId(), refreshToken));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = AuthCookies.REFRESH, required = false) String refreshToken) {
        auth.revoke(refreshToken);
        boolean secure = properties.secureCookies();
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, AuthCookies.clearAccess(secure).toString())
                .header(HttpHeaders.SET_COOKIE, AuthCookies.clearRefresh(secure).toString())
                .build();
    }

    @GetMapping("/me")
    public SessionView me(@AuthenticationPrincipal Jwt jwt) {
        String company = jwt.getClaimAsString(TenantContextFilter.COMPANY_CLAIM);
        return auth.session(UUID.fromString(jwt.getSubject()), company == null ? null : UUID.fromString(company));
    }

    // ---------------------------------------------------------------- бот і мобільний (Bearer)

    @PostMapping("/token")
    public TokenResponse token(@RequestBody LoginRequest request) {
        return toTokenResponse(auth.login(request.phone(), request.password(), request.companyId()));
    }

    @PostMapping("/token/refresh")
    public TokenResponse tokenRefresh(@RequestBody RefreshRequest request) {
        return toTokenResponse(auth.refresh(request.refreshToken()));
    }

    // ---------------------------------------------------------------- допоміжне

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<Map<String, String>> handle(AuthException e) {
        return ResponseEntity.status(e.status()).body(Map.of("error", e.code()));
    }

    private ResponseEntity<SessionView> withCookies(LoginResult result) {
        boolean secure = properties.secureCookies();
        AuthService.IssuedTokens tokens = result.tokens();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE,
                        AuthCookies.access(tokens.accessToken(), properties.accessTokenTtl(), secure).toString())
                .header(HttpHeaders.SET_COOKIE,
                        AuthCookies.refresh(tokens.refreshToken(), properties.refreshTokenTtl(), secure).toString())
                .body(result.session());
    }

    private static TokenResponse toTokenResponse(LoginResult result) {
        AuthService.IssuedTokens t = result.tokens();
        return new TokenResponse(t.accessToken(), t.accessTokenExpiresAt(), t.refreshToken(),
                t.refreshTokenExpiresAt(), result.session());
    }
}
