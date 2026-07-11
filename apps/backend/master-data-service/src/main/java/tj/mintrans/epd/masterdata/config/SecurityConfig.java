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
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

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
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable()) // stateless API — CSRF не нужен
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/.well-known/**").permitAll()
                        // Публичная проверка QR (инспектор без логина)
                        .requestMatchers("/api/v1/verify/**").permitAll()
                        // TODO(prod, ВАЖНО — аудит): GET и межсервисный PATCH одометра сейчас permitAll,
                        // т.к. поток агрегатора (ЧУРА/НЕРУ) и часть вызовов waybill-service идут БЕЗ
                        // пользовательского токена. Закрыть client-credentials токеном сервисного
                        // аккаунта (waybill-service, aggregator) и требовать authenticated() —
                        // иначе анонимное чтение ПДн. Требует инфраструктуры сервисной аутентификации.
                        .requestMatchers(HttpMethod.GET, "/api/v1/**").permitAll()
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/vehicles/*/odometer").permitAll()
                        // TODO: ВРЕМЕННО открыто. Следующий этап — scoped-токены
                        // агрегаторов (API_INTEGRATOR): убрать permitAll и требовать
                        // client-credentials токен с ограничением по клиенту.
                        .requestMatchers("/api/v1/aggregator/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));
        return http.build();
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
