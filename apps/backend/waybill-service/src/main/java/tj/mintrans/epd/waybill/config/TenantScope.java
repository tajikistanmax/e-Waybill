package tj.mintrans.epd.waybill.config;

import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tj.mintrans.epd.waybill.client.MasterDataClient;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Область видимости организаций для текущего пользователя в waybill-service.
 *
 * <p>Плоская мультиарендность (claim {@code organization_rma}) расширена
 * иерархией «компания → филиал»:
 * <ul>
 *   <li>платформенная роль (SYSTEM_ADMIN / MINTRANS_ANALYST / INSPECTOR / API_INTEGRATOR) —
 *       область не ограничена ({@link #isBounded()} = false);
 *   <li>администратор компании (COMPANY_ADMIN) — своя организация и все её филиалы
 *       (набор берётся из master-data token-relay вызовом — он сам применяет скоуп);
 *   <li>остальные тенант-роли — только своя организация.
 * </ul>
 */
@Component
public class TenantScope {

    /** Кэш «своя РМА → набор РМА области», 60 с — COMPANY_ADMIN не самый частый пользователь. */
    private record Cached(Set<String> rmas, long expiresAt) {
    }

    private static final long TTL_MS = 60_000;

    private final CurrentUser currentUser;
    private final MasterDataClient masterData;
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();

    public TenantScope(CurrentUser currentUser, MasterDataClient masterData) {
        this.currentUser = currentUser;
        this.masterData = masterData;
    }

    /** true — запрос нужно ограничить набором {@link #rmas()}. */
    public boolean isBounded() {
        return currentUser.isTenantScoped();
    }

    /**
     * РМА организаций, доступных пользователю. Для платформенной роли — пустое множество
     * (сначала проверьте {@link #isBounded()}); для тенанта без claim — {@code {"__none__"}}.
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
        if (!currentUser.hasRole("COMPANY_ADMIN")) {
            full = Set.of(ownRma);
        } else {
            Cached c = cache.get(ownRma);
            long now = System.currentTimeMillis();
            if (c != null && c.expiresAt() > now) {
                full = c.rmas();
            } else {
                Set<String> resolved = new LinkedHashSet<>();
                resolved.add(ownRma);
                resolved.addAll(masterData.scopedOrganizationRmas());
                cache.put(ownRma, new Cached(resolved, now + TTL_MS));
                full = resolved;
            }
        }
        // Переключатель филиала в шапке (COMPANY_ADMIN): X-Org-Scope сужает область до
        // одного филиала, если он входит в полную область пользователя.
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

    /** true — организация {@code rma} видна пользователю (или область не ограничена). */
    public boolean contains(String rma) {
        return !isBounded() || rmas().contains(rma);
    }

    /** true — пользователь может писать в организацию {@code rma}. */
    public boolean canWrite(String rma) {
        return contains(rma);
    }

    /**
     * Единственная РМА для запросов, ещё не переведённых на набор организаций
     * (отчёты, справки). Для тенанта — claim; {@code "__none__"} если claim нет.
     * Не учитывает филиалы — used как есть до полной миграции этих запросов.
     */
    public String singleRmaOrNone() {
        return currentUser.organizationRma().orElse("__none__");
    }
}
