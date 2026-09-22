package tj.mintrans.epd.masterdata.service;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import tj.mintrans.epd.masterdata.config.TenantScope;
import tj.mintrans.epd.masterdata.repository.OrganizationRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Общая часть постраничных реестров (ТС / водители / сотрудники): область организаций
 * и подстрочный поиск. Вынесено из контроллеров, чтобы три реестра отбирали одинаково.
 *
 * <p>Область считается пересечением трёх ограничений: мультиарендность (что вообще видно
 * пользователю), явно выбранная организация и географический отбор регион/город. Пустой
 * результат (список без элементов) означает «видно ничего» и отличается от «без ограничения»
 * ({@link Optional#empty()}) — иначе администратор платформы получал бы пустой реестр там,
 * где фильтры не заданы.
 */
@Service
public class RegistryQuery {

    /** Потолок размера страницы: защита от «size=100000» в обход пагинации. */
    public static final int MAX_PAGE_SIZE = 200;

    private final TenantScope tenantScope;
    private final OrganizationRepository organizations;

    public RegistryQuery(TenantScope tenantScope, OrganizationRepository organizations) {
        this.tenantScope = tenantScope;
        this.organizations = organizations;
    }

    /** Страница с безопасными границами и стабильной сортировкой. */
    public Pageable pageable(int page, int size, String sortField) {
        int p = Math.max(0, page);
        int s = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return PageRequest.of(p, s, Sort.by(Sort.Direction.ASC, sortField));
    }

    /**
     * Организации, которыми ограничена выборка. {@link Optional#empty()} — ограничения нет
     * (платформенная роль без фильтров); присутствующий список — ровно эти организации.
     */
    public Optional<List<UUID>> organizationScope(String organizationRma, Short regionId, String cityName) {
        Optional<List<UUID>> scope = tenantScope.isBounded()
                ? Optional.of(List.copyOf(tenantScope.organizationIds()))
                : Optional.empty();

        if (organizationRma != null && !organizationRma.isBlank()) {
            var org = organizations.findByRma(organizationRma.trim());
            // Несуществующая или чужая организация — пустая выборка, а не молчаливое расширение области.
            List<UUID> one = org.map(o -> List.of(o.getId())).orElseGet(List::of);
            scope = Optional.of(intersect(scope, one));
        }
        boolean geo = regionId != null || (cityName != null && !cityName.isBlank());
        if (geo) {
            var ids = organizations.findIdsByRegionAndCity(regionId, blankToNull(cityName));
            scope = Optional.of(intersect(scope, ids));
        }
        return scope;
    }

    /** Организации, чьё название содержит подстроку — чтобы поиск по реестру находил и по компании. */
    public List<UUID> organizationIdsByName(String q) {
        if (q == null || q.isBlank()) {
            return List.of();
        }
        return organizations.findIdsByNameLike(q.trim().toLowerCase());
    }

    /**
     * Спецификация отбора: область организаций И подстрочный поиск по текстовым полям
     * сущности ИЛИ по названию организации.
     *
     * @param scope      область организаций (см. {@link #organizationScope})
     * @param q          строка поиска (может быть пустой)
     * @param textFields поля сущности, по которым ищем
     * @param orgIdsByName организации, подходящие под строку поиска по названию
     */
    public <T> Specification<T> specification(Optional<List<UUID>> scope, String q,
                                              List<String> textFields, List<UUID> orgIdsByName) {
        String needle = q == null ? "" : q.trim().toLowerCase();
        return (root, query, cb) -> {
            List<Predicate> and = new ArrayList<>();
            if (scope.isPresent()) {
                List<UUID> ids = scope.get();
                if (ids.isEmpty()) {
                    return cb.disjunction(); // видно ничего
                }
                and.add(root.get("organizationId").in(ids));
            }
            if (!needle.isEmpty()) {
                String like = "%" + needle + "%";
                List<Predicate> or = new ArrayList<>();
                for (String f : textFields) {
                    or.add(cb.like(cb.lower(cb.coalesce(root.get(f).as(String.class), "")), like));
                }
                if (!orgIdsByName.isEmpty()) {
                    or.add(root.get("organizationId").in(orgIdsByName));
                }
                and.add(cb.or(or.toArray(new Predicate[0])));
            }
            return and.isEmpty() ? cb.conjunction() : cb.and(and.toArray(new Predicate[0]));
        };
    }

    private static List<UUID> intersect(Optional<List<UUID>> current, List<UUID> next) {
        if (current.isEmpty()) {
            return next;
        }
        var keep = new ArrayList<>(current.get());
        keep.retainAll(next);
        return keep;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
