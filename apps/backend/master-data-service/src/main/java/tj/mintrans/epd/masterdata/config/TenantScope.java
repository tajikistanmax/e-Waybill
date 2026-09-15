package tj.mintrans.epd.masterdata.config;

import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tj.mintrans.epd.masterdata.domain.Organization;
import tj.mintrans.epd.masterdata.repository.OrganizationRepository;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Область видимости организаций для текущего пользователя.
 *
 * <p>Плоская мультиарендность (claim {@code organization_rma}) расширена
 * иерархией «компания → филиал» ({@code organization.parent_rma}):
 * <ul>
 *   <li>платформенная роль (SYSTEM_ADMIN / MINTRANS_ANALYST / API_INTEGRATOR) и
 *       анонимные внутренние вызовы — область не ограничена ({@link #isBounded()} = false);
 *   <li>администратор компании (COMPANY_ADMIN) — своя организация <b>и все её филиалы</b>;
 *   <li>остальные тенант-роли (BRANCH_ADMIN, диспетчер, врач…) — только своя организация.
 * </ul>
 *
 * <p>Заменяет прямые обращения к {@code currentUser.organizationRma()} в местах
 * фильтрации/защиты по организации: раньше это был один РМА, теперь — набор.
 */
@Component
public class TenantScope {

    private final CurrentUser currentUser;
    private final OrganizationRepository organizations;

    TenantScope(CurrentUser currentUser, OrganizationRepository organizations) {
        this.currentUser = currentUser;
        this.organizations = organizations;
    }

    /** true — запрос нужно ограничить набором организаций {@link #rmas()}. */
    public boolean isBounded() {
        return currentUser.isTenantScoped();
    }

    /**
     * РМА организаций, доступных пользователю. Для платформенной роли возвращает
     * пустое множество (ограничение не применяется — сначала проверьте {@link #isBounded()}).
     * Если у тенанта нет claim {@code organization_rma} — {@code {"__none__"}} (ничего не видно).
     */
    public Set<String> rmas() {
        if (!isBounded()) {
            return Set.of();
        }
        Optional<String> own = currentUser.organizationRma();
        if (own.isEmpty()) {
            return Set.of("__none__");
        }
        String ownRma = own.get();
        Set<String> full;
        if (currentUser.hasRole("COMPANY_ADMIN")) {
            full = new LinkedHashSet<>();
            full.add(ownRma);
            organizations.findByParentRma(ownRma).forEach(o -> full.add(o.getRma()));
        } else {
            full = Set.of(ownRma);
        }
        // Переключатель филиала в шапке (COMPANY_ADMIN): заголовок X-Org-Scope сужает
        // область до одного филиала, если он входит в полную область пользователя.
        String pick = header("X-Org-Scope");
        if (pick != null && !pick.isBlank() && full.contains(pick.trim())) {
            return Set.of(pick.trim());
        }
        return full;
    }

    private static String header(String name) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest().getHeader(name);
        }
        return null;
    }

    /** Организации области как сущности (для резолва id). Пусто для платформенной роли. */
    public List<Organization> organizations() {
        return isBounded() ? organizations.findByRmaIn(rmas()) : List.of();
    }

    /** id организаций области (для фильтров {@code organization_id IN (…)}). */
    public List<UUID> organizationIds() {
        return organizations().stream().map(Organization::getId).toList();
    }

    /** true — организация {@code rma} видна пользователю (или область не ограничена). */
    public boolean contains(String rma) {
        return !isBounded() || rmas().contains(rma);
    }

    /**
     * true — пользователь может писать в организацию {@code rma}
     * (своя организация либо — для администратора компании — её филиал).
     */
    public boolean canWrite(String rma) {
        return contains(rma);
    }
}
