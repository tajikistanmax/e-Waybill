package tj.mintrans.epd.masterdata.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
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

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            @org.springframework.beans.factory.annotation.Value("${epd.security.docs-open:true}") boolean docsOpen) throws Exception {
        http
                .csrf(csrf -> csrf.disable()) // stateless API — CSRF не нужен
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    auth
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/.well-known/**").permitAll();
                    // OpenAPI/Swagger: dev открыт, прод (DOCS_OPEN=false) — под аутентификацией.
                    if (docsOpen) {
                        auth.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll();
                    } else {
                        auth.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").authenticated();
                    }
                    auth
                        // Публичная проверка QR (инспектор без логина)
                        .requestMatchers("/api/v1/verify/**").permitAll()
                        // Публичные контакты поддержки для страницы входа (пре-аутентификация)
                        .requestMatchers(HttpMethod.GET, "/api/v1/settings/public").permitAll()
                        // Изображения бренда (логотип/фон входа) — публичны: страница входа
                        // читает их до аутентификации. Загрузка/сброс (POST/DELETE) — ниже под токеном.
                        .requestMatchers(HttpMethod.GET, "/api/v1/branding/**").permitAll()
                        // Названия типов ПЛ — публичное справочное отображение (tType до/после входа).
                        .requestMatchers(HttpMethod.GET, "/api/v1/classifiers/waybill-types").permitAll()
                        // GET и межсервисный PATCH одометра требуют токена (закрыт анонимный доступ
                        // к ПДн — аудит). Пользователь ходит со своим JWT (тенант-фильтр по организации),
                        // а межсервисные вызовы waybill-service без пользователя (агрегатор ЧУРА/НЕРУ,
                        // планировщик) — с сервисным client-credentials токеном epd-aggregator
                        // (роль API_INTEGRATOR = платформенное чтение всех организаций).
                        .requestMatchers(HttpMethod.GET, "/api/v1/**").authenticated()
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/vehicles/*/odometer").authenticated()
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
