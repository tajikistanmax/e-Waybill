package tj.mintrans.epd.waybill.config;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Текущий пользователь из JWT (Keycloak realm "epd").
 * Мультиарендность: не-админ видит только данные своей организации
 * (claim "organization_rma"). Анонимные (внутренние) вызовы не фильтруются.
 */
@Component
public class CurrentUser {

    private static final String ORGANIZATION_CLAIM = "organization_rma";

    /** true, если запрос пришёл с JWT (а не анонимный внутренний вызов). */
    public boolean isJwtAuthenticated() {
        return authentication() instanceof JwtAuthenticationToken;
    }

    /**
     * Роли с доступом к данным всех организаций (не применяется тенант-фильтр):
     * SYSTEM_ADMIN, MINTRANS_ANALYST (аналитика), INSPECTOR (дорожный контроль читает/
     * блокирует ПЛ любой организации), API_INTEGRATOR (сервисный аккаунт агрегатора и
     * межсервисных вызовов — в проде без него get() отдавал бы 404 на любой ПЛ).
     */
    public boolean isPlatformAdmin() {
        Authentication auth = authentication();
        if (auth == null) {
            return false;
        }
        return auth.getAuthorities().stream().anyMatch(a ->
                "ROLE_SYSTEM_ADMIN".equals(a.getAuthority())
                        || "ROLE_MINTRANS_ANALYST".equals(a.getAuthority())
                        || "ROLE_INSPECTOR".equals(a.getAuthority())
                        || "ROLE_API_INTEGRATOR".equals(a.getAuthority()));
    }

    /** РМА организации пользователя из claim "organization_rma" (если есть). */
    public Optional<String> organizationRma() {
        if (authentication() instanceof JwtAuthenticationToken jwt
                && jwt.getToken().getClaim(ORGANIZATION_CLAIM) instanceof String rma
                && !rma.isBlank()) {
            return Optional.of(rma);
        }
        return Optional.empty();
    }

    /** true, если к запросу нужно применять фильтр по организации пользователя. */
    public boolean isTenantScoped() {
        return isJwtAuthenticated() && !isPlatformAdmin();
    }

    private Authentication authentication() {
        return SecurityContextHolder.getContext().getAuthentication();
    }
}
