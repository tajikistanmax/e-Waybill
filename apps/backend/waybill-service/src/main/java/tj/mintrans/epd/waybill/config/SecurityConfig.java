package tj.mintrans.epd.waybill.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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

    /**
     * Legacy-агрегаторы (ЧУРА/НЕРУ) исторически ходят без токена — в dev оставлено
     * открытым (true). В проде AGGREGATOR_OPEN=false: требуется client-credentials
     * токен клиента epd-aggregator с ролью API_INTEGRATOR (см. infra/keycloak/epd-realm.json).
     */
    private final boolean aggregatorOpen;

    public SecurityConfig(@org.springframework.beans.factory.annotation.Value("${epd.security.aggregator-open:true}") boolean aggregatorOpen) {
        this.aggregatorOpen = aggregatorOpen;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable()) // stateless API — CSRF не нужен
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    auth
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/.well-known/**").permitAll()
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
