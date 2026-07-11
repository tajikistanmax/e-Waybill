package tj.mintrans.epd.waybill.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Защита API: stateless resource server, JWT от Keycloak (realm "epd").
 * Роли берутся из claim realm_access.roles и превращаются в ROLE_<имя>.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * Legacy-агрегаторы (ЧУРА/НЕРУ) исторически ходят без токена — в dev оставлено
     * открытым (true). В проде AGGREGATOR_OPEN=false: требуется client-credentials
     * токен клиента epd-aggregator с ролью API_INTEGRATOR (см. infra/keycloak/epd-realm.json).
     */
    private final boolean aggregatorOpen;

    /**
     * OpenAPI-спека и Swagger UI: в dev открыты (true), в проде DOCS_OPEN=false —
     * схема API закрывается аутентификацией (не раскрываем поверхность атаки анониму).
     */
    private final boolean docsOpen;

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    public SecurityConfig(
            @org.springframework.beans.factory.annotation.Value("${epd.security.aggregator-open:true}") boolean aggregatorOpen,
            @org.springframework.beans.factory.annotation.Value("${epd.security.docs-open:true}") boolean docsOpen) {
        this.aggregatorOpen = aggregatorOpen;
        this.docsOpen = docsOpen;
    }

    /** Прод-предупреждение: открытый агрегатор без токена — только для dev. */
    @PostConstruct
    void warnAggregatorOpen() {
        if (aggregatorOpen) {
            log.warn("AGGREGATOR_OPEN=true: /api/v1/aggregator/** ОТКРЫТ без токена (dev-режим). "
                    + "Для прода задайте AGGREGATOR_OPEN=false — обязателен client-credentials токен epd-aggregator.");
        }
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable()) // stateless API — CSRF не нужен
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    auth
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/.well-known/**").permitAll();
                    if (docsOpen) {
                        auth.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll();
                    } else {
                        auth.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").authenticated();
                    }
                    auth
                        // Публичная проверка QR (инспектор без логина)
                        .requestMatchers("/api/v1/verify/**").permitAll()
                        // Webhook платёжного шлюза — аутентификация общим секретом
                        // в заголовке X-Payment-Secret (см. PaymentWebhookController)
                        .requestMatchers("/api/v1/payments/webhook").permitAll();
                    if (aggregatorOpen) {
                        auth.requestMatchers("/api/v1/aggregator/**").permitAll();
                    } else {
                        auth.requestMatchers("/api/v1/aggregator/**").hasRole("API_INTEGRATOR");
                    }
                    auth
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated();
                })
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));
        return http.build();
    }

    /**
     * JWT-декодер: подпись + issuer (Keycloak realm epd) всегда; audience — опционально.
     * epd.security.required-audience задан (прод) → токен обязан нести этот aud (защита от
     * приёма токена, выпущенного для другого клиента/цели того же realm). Пусто (dev) →
     * поведение как у автоконфигурации (issuer+exp), регресс не меняется.
     */
    @Bean
    public JwtDecoder jwtDecoder(
            @org.springframework.beans.factory.annotation.Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
            @org.springframework.beans.factory.annotation.Value("${epd.security.required-audience:}") String requiredAudience) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withIssuerLocation(issuer).build();
        var validators = new ArrayList<OAuth2TokenValidator<Jwt>>();
        validators.add(JwtValidators.createDefaultWithIssuer(issuer));
        if (requiredAudience != null && !requiredAudience.isBlank()) {
            validators.add(new JwtClaimValidator<List<String>>("aud",
                    aud -> aud != null && aud.contains(requiredAudience)));
        }
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
        return decoder;
    }

    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(SecurityConfig::realmRoles);
        return converter;
    }

    /** realm_access.roles → ROLE_<имя> (Keycloak realm roles). */
    private static Collection<GrantedAuthority> realmRoles(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        if (realmAccess == null || !(realmAccess.get("roles") instanceof Collection<?> roles)) {
            return List.of();
        }
        return roles.stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
    }
}
