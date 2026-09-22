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
    private static final String RMA_CLAIM = "rma";

    /** true, если запрос пришёл с JWT (а не анонимный внутренний вызов). */
    public boolean isJwtAuthenticated() {
        return authentication() instanceof JwtAuthenticationToken;
    }

    /** РМА самого субъекта из claim "rma" (для водителя — его РМА водителя). */
    public Optional<String> rma() {
        if (authentication() instanceof JwtAuthenticationToken jwt
                && jwt.getToken().getClaim(RMA_CLAIM) instanceof String r && !r.isBlank()) {
            return Optional.of(r);
        }
        return Optional.empty();
    }

    /** ФИО из JWT (claim {@code name}, если Keycloak его отдаёт) — для отметок «кто подтвердил». */
    public Optional<String> fullName() {
        if (authentication() instanceof JwtAuthenticationToken jwt
                && jwt.getToken().getClaim("name") instanceof String n && !n.isBlank()) {
            return Optional.of(n);
        }
        return Optional.empty();
    }

    /** preferred_username из JWT — человекочитаемый актор для журналов (created_by/confirmed_by). */
    public Optional<String> username() {
        if (authentication() instanceof JwtAuthenticationToken jwt
                && jwt.getToken().getClaim("preferred_username") instanceof String u && !u.isBlank()) {
            return Optional.of(u);
        }
        return Optional.empty();
    }

    /** true, если у текущего субъекта есть realm-роль role (без префикса ROLE_). */
    public boolean hasRole(String role) {
        Authentication auth = authentication();
        if (auth == null) {
            return false;
        }
        String target = "ROLE_" + role;
        return auth.getAuthorities().stream().anyMatch(a -> target.equals(a.getAuthority()));
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

    /**
     * Идентификаторы контрагентов (Client master-data) внешнего пользователя — claim {@code client_ids}
     * (атрибут Keycloak {@code clientIds}, UUID через запятую). Кабинеты грузоотправителя/экспедитора
     * (MIGRATION.md 1.1/3.11, legacy {@code has_client_senders/has_client_forwarders}).
     */
    public java.util.Set<String> clientIds() {
        if (authentication() instanceof JwtAuthenticationToken jwt) {
            Object raw = jwt.getToken().getClaim("client_ids");
            java.util.Set<String> out = new java.util.LinkedHashSet<>();
            if (raw instanceof java.util.Collection<?> c) {
                c.forEach(v -> { if (v != null && !v.toString().isBlank()) out.add(v.toString().trim().toLowerCase()); });
            } else if (raw instanceof String s) {
                for (String part : s.split(",")) {
                    if (!part.isBlank()) out.add(part.trim().toLowerCase());
                }
            }
            return out;
        }
        return java.util.Set.of();
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
