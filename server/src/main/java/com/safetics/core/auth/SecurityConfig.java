package com.safetics.core.auth;

import java.time.Clock;
import java.util.Set;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

/**
 * Безпека API: stateless, JWT (HS256) у заголовку або httpOnly-cookie.
 *
 * <p>Дозволи з токена (claim {@code perms}) стають authorities, тож у коді перевірка виглядає як
 * {@code @PreAuthorize("hasAuthority('stock.write_off')")}. Назви ролей у коді не перевіряються.
 *
 * <p>CSRF вимкнено: cookie мають SameSite=Lax, а API приймає лише JSON, тож чужий сайт не може
 * надіслати запит від імені користувача.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfig {

    static final Set<String> PUBLIC_AUTH_PATHS = Set.of(
            "/api/v1/auth/login",
            "/api/v1/auth/token",
            "/api/v1/auth/refresh",
            "/api/v1/auth/token/refresh",
            "/api/v1/auth/logout");

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("perms");
        authorities.setAuthorityPrefix("");
        JwtAuthenticationConverter jwtConverter = new JwtAuthenticationConverter();
        jwtConverter.setJwtGrantedAuthoritiesConverter(authorities);

        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/info", "/error").permitAll()
                        // Опис API; у профілі без dev springdoc вимкнений і ці адреси дають 404
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers(HttpMethod.POST, PUBLIC_AUTH_PATHS.toArray(String[]::new)).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .bearerTokenResolver(new CookieOrHeaderBearerTokenResolver())
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter)))
                .addFilterAfter(new TenantContextFilter(), BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    JwtEncoder jwtEncoder(SecurityProperties properties) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(properties.signingKey()));
    }

    @Bean
    JwtDecoder jwtDecoder(SecurityProperties properties) {
        return NimbusJwtDecoder.withSecretKey(properties.signingKey())
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    /** BCrypt за замовчуванням; префікс {bcrypt} у хеші дозволяє пізніше змінити алгоритм. */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
