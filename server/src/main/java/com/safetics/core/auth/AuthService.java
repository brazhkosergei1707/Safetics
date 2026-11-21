package com.safetics.core.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import com.safetics.core.auth.AuthDtos.CompanyView;
import com.safetics.core.auth.AuthDtos.SessionView;
import com.safetics.core.auth.AuthDtos.UserView;
import com.safetics.core.tenancy.TenantContext;

/**
 * Вхід за телефоном і паролем, вибір компанії, оновлення і відкликання токенів.
 *
 * <p>Токен доступу — JWT на 15 хвилин: {@code sub} = користувач, {@code cid} = обрана компанія,
 * {@code perms} = дозволи ролі в цій компанії. Зміна ролі набуває чинності з наступним оновленням
 * токена. Токен оновлення — випадковий рядок, у базі лише його SHA-256, кожен одноразовий.
 */
@Service
public class AuthService {

    static final String ISSUER = "safetics";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient db;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;
    private final SecurityProperties properties;
    private final Clock clock;

    /** Хеш для порівняння, коли користувача не знайдено: відповідь займає стільки ж часу. */
    private final String dummyHash;

    public AuthService(JdbcClient db, PasswordEncoder passwordEncoder, JwtEncoder jwtEncoder,
                       SecurityProperties properties, Clock clock) {
        this.db = db;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode("timing-equalizer-not-a-password");
    }

    public record IssuedTokens(String accessToken, Instant accessTokenExpiresAt,
                               String refreshToken, Instant refreshTokenExpiresAt) {
    }

    public record LoginResult(IssuedTokens tokens, SessionView session) {
    }

    private record UserRow(UUID id, String phone, String fullName, String passwordHash, String status) {
    }

    private record RefreshRow(UUID userId, UUID companyId) {
    }

    // ---------------------------------------------------------------- публічні операції

    public LoginResult login(String phone, String password, UUID requestedCompanyId) {
        UserRow user = authenticate(phone, password);
        List<CompanyView> companies = companiesOf(user.id());

        UUID companyId = requestedCompanyId;
        if (companyId != null && !isMember(companies, companyId)) {
            throw AuthException.notAMember();
        }
        if (companyId == null && companies.size() == 1) {
            companyId = companies.getFirst().id();
        }
        return new LoginResult(issue(user.id(), companyId), session(user, companyId, companies));
    }

    public LoginResult refresh(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw AuthException.invalidRefreshToken();
        }
        RefreshRow row = db.sql("""
                        update refresh_token
                        set revoked_at = now()
                        where token_hash = ? and revoked_at is null and expires_at > now()
                        returning user_id, company_id
                        """)
                .param(sha256(rawRefreshToken))
                .query((rs, n) -> new RefreshRow(rs.getObject("user_id", UUID.class),
                        rs.getObject("company_id", UUID.class)))
                .optional()
                .orElseThrow(AuthException::invalidRefreshToken);

        UserRow user = findUserById(row.userId())
                .filter(u -> "active".equals(u.status()))
                .orElseThrow(AuthException::invalidRefreshToken);
        List<CompanyView> companies = companiesOf(user.id());
        // Якщо людину за цей час вивели з компанії, сесія продовжується без компанії
        UUID companyId = row.companyId() != null && isMember(companies, row.companyId()) ? row.companyId() : null;
        return new LoginResult(issue(user.id(), companyId), session(user, companyId, companies));
    }

    public LoginResult switchCompany(UUID userId, UUID companyId, String currentRefreshToken) {
        UserRow user = findUserById(userId)
                .filter(u -> "active".equals(u.status()))
                .orElseThrow(AuthException::invalidCredentials);
        List<CompanyView> companies = companiesOf(userId);
        if (companyId == null || !isMember(companies, companyId)) {
            throw AuthException.notAMember();
        }
        revoke(currentRefreshToken);
        return new LoginResult(issue(userId, companyId), session(user, companyId, companies));
    }

    public void revoke(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return;
        }
        db.sql("update refresh_token set revoked_at = now() where token_hash = ? and revoked_at is null")
                .param(sha256(rawRefreshToken))
                .update();
    }

    public SessionView session(UUID userId, UUID companyId) {
        UserRow user = findUserById(userId).orElseThrow(AuthException::invalidCredentials);
        List<CompanyView> companies = companiesOf(userId);
        UUID effectiveCompany = companyId != null && isMember(companies, companyId) ? companyId : null;
        return session(user, effectiveCompany, companies);
    }

    // ---------------------------------------------------------------- внутрішнє

    private UserRow authenticate(String rawPhone, String password) {
        if (rawPhone == null || password == null || password.isEmpty()) {
            throw AuthException.invalidCredentials();
        }
        Optional<UserRow> user = db.sql("""
                        select id, phone, full_name, password_hash, status
                        from app_user where phone = ?
                        """)
                .param(normalizePhone(rawPhone))
                .query((rs, n) -> new UserRow(rs.getObject("id", UUID.class), rs.getString("phone"),
                        rs.getString("full_name"), rs.getString("password_hash"), rs.getString("status")))
                .optional();

        String hash = user.map(UserRow::passwordHash).orElse(null);
        boolean matches = passwordEncoder.matches(password, hash != null ? hash : dummyHash);
        if (user.isEmpty() || hash == null || !matches || !"active".equals(user.get().status())) {
            throw AuthException.invalidCredentials();
        }
        return user.get();
    }

    private Optional<UserRow> findUserById(UUID userId) {
        return db.sql("select id, phone, full_name, password_hash, status from app_user where id = ?")
                .param(userId)
                .query((rs, n) -> new UserRow(rs.getObject("id", UUID.class), rs.getString("phone"),
                        rs.getString("full_name"), rs.getString("password_hash"), rs.getString("status")))
                .optional();
    }

    /** Компанії користувача. Запит іде з контекстом «лише користувач», RLS пускає лише до його членств. */
    private List<CompanyView> companiesOf(UUID userId) {
        return TenantContext.callAs(null, userId, () -> db.sql("""
                        select c.id, c.name, r.code as role
                        from membership m
                                 join company c on c.id = m.company_id
                                 left join role r on r.id = m.role_id
                        where m.user_id = ? and m.status = 'active' and c.status = 'active'
                        order by c.name
                        """)
                .param(userId)
                .query((rs, n) -> new CompanyView(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getString("role")))
                .list());
    }

    private List<String> permissionsOf(UUID userId, UUID companyId) {
        if (companyId == null) {
            return List.of();
        }
        return TenantContext.callAs(companyId, userId, () -> db.sql("""
                        select rp.permission_code
                        from membership m
                                 join role_permission rp on rp.role_id = m.role_id
                        where m.user_id = ? and m.company_id = ? and m.status = 'active'
                        order by 1
                        """)
                .params(userId, companyId)
                .query(String.class)
                .list());
    }

    private IssuedTokens issue(UUID userId, UUID companyId) {
        Instant now = clock.instant();
        Instant accessExpiresAt = now.plus(properties.accessTokenTtl());
        Instant refreshExpiresAt = now.plus(properties.refreshTokenTtl());

        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(accessExpiresAt)
                .claim("perms", permissionsOf(userId, companyId));
        if (companyId != null) {
            claims.claim(TenantContextFilter.COMPANY_CLAIM, companyId.toString());
        }
        String accessToken = jwtEncoder
                .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build()))
                .getTokenValue();

        String refreshToken = newRefreshToken();
        db.sql("insert into refresh_token (user_id, company_id, token_hash, expires_at) values (?, ?, ?, ?)")
                .params(userId, companyId, sha256(refreshToken), OffsetDateTime.ofInstant(refreshExpiresAt, ZoneOffset.UTC))
                .update();

        return new IssuedTokens(accessToken, accessExpiresAt, refreshToken, refreshExpiresAt);
    }

    private SessionView session(UserRow user, UUID companyId, List<CompanyView> companies) {
        CompanyView current = companies.stream().filter(c -> c.id().equals(companyId)).findFirst().orElse(null);
        return new SessionView(
                new UserView(user.id(), user.fullName(), user.phone()),
                current,
                companies,
                permissionsOf(user.id(), current == null ? null : current.id()));
    }

    private static boolean isMember(List<CompanyView> companies, UUID companyId) {
        return companies.stream().anyMatch(c -> c.id().equals(companyId));
    }

    /** «+380 (50) 123-45-67» → «+380501234567». Номери зберігаються в міжнародному форматі. */
    static String normalizePhone(String raw) {
        return "+" + raw.replaceAll("[^0-9]", "");
    }

    private static String newRefreshToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
