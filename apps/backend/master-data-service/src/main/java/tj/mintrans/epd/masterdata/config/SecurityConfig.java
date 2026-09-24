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
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
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
            HttpSecurity http, RateLimitFilter rateLimitFilter,
            @org.springframework.beans.factory.annotation.Value("${epd.security.docs-open:true}") boolean docsOpen) throws Exception {
        http
                .addFilterBefore(rateLimitFilter, BearerTokenAuthenticationFilter.class)
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
                        // Вход в платформу: выдача и обновление токена, смена пароля, открытые
                        // ключи подписи. Это единственная дверь без токена — ею же пользуется
                        // служба путевых листов, чтобы проверять подпись (набор ключей).
                        // second-factor — второй шаг входа (код из приложения); пускает только
                        // по одноразовому ключу, выданному после верного пароля.
                        .requestMatchers("/api/v1/auth/token", "/api/v1/auth/second-factor", "/api/v1/auth/refresh",
                                "/api/v1/auth/logout", "/api/v1/auth/password", "/api/v1/auth/jwks").permitAll()
                        // Публичная проверка QR (инспектор без логина)
                        .requestMatchers("/api/v1/verify/**").permitAll()
                        // Публичные контакты поддержки для страницы входа (пре-аутентификация)
                        .requestMatchers(HttpMethod.GET, "/api/v1/settings/public").permitAll()
                        // Изображения бренда (логотип/фон входа) — публичны: страница входа
                        // читает их до аутентификации. Загрузка/сброс (POST/DELETE) — ниже под токеном.
                        .requestMatchers(HttpMethod.GET, "/api/v1/branding/**").permitAll()
                        // Названия типов/статусов ПЛ — публичное справочное отображение
                        // (tType/tStatus до/после входа, в т.ч. страница проверки QR).
                        .requestMatchers(HttpMethod.GET, "/api/v1/classifiers/waybill-types").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/classifiers/waybill-statuses").permitAll()
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
     * Проверка токена. Токены выпускает сама эта служба ({@code /api/v1/auth/token}), поэтому
     * подпись сверяется по ключу из базы напрямую — без обращения к себе же по сети.
     *
     * <p>До 23.09.2026 токены выпускал Keycloak и ключи тянулись по HTTP; после отказа от
     * Keycloak (решение владельца) издатель — платформа, набор ключей живёт в
     * {@code auth_signing_key} и переживает перезапуск.</p>
     */
    @Bean
    public JwtDecoder jwtDecoder(
            tj.mintrans.epd.masterdata.auth.SigningKeys keys,
            tj.mintrans.epd.masterdata.auth.TokenIssuer tokenIssuer) {
        var jwkSource = new com.nimbusds.jose.jwk.source.ImmutableJWKSet<com.nimbusds.jose.proc.SecurityContext>(
                keys.publicSet());
        var processor = new com.nimbusds.jwt.proc.DefaultJWTProcessor<com.nimbusds.jose.proc.SecurityContext>();
        processor.setJWSKeySelector(new com.nimbusds.jose.proc.JWSVerificationKeySelector<>(
                com.nimbusds.jose.JWSAlgorithm.RS256, jwkSource));
        // Проверку самих claim'ов делает Spring ниже — здесь отключаем встроенную, иначе она
        // сработает раньше и вернёт менее внятную ошибку.
        processor.setJWTClaimsSetVerifier((claims, context) -> { });
        NimbusJwtDecoder decoder = new NimbusJwtDecoder(processor);
        var validators = new ArrayList<OAuth2TokenValidator<Jwt>>();
        validators.add(JwtValidators.createDefaultWithIssuer(tokenIssuer.issuer()));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
        return decoder;
    }

    /**
     * Хеширование паролей — BCrypt. Пароли платформы хранятся только хешем
     * ({@code app_user.password_hash}); восстановить исходный пароль по базе нельзя.
     */
    @Bean
    public org.springframework.security.crypto.password.PasswordEncoder passwordEncoder() {
        return new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
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
