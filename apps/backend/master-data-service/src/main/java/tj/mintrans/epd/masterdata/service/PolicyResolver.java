package tj.mintrans.epd.masterdata.service;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import tj.mintrans.epd.masterdata.config.CacheConfig;
import tj.mintrans.epd.masterdata.domain.Policy;
import tj.mintrans.epd.masterdata.repository.PolicyRepository;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Разрешение эффективных значений правил движка политик.
 * Побеждает наиболее специфичный уровень: VEHICLE_TYPE > ORGANIZATION > NATIONAL.
 */
@Service
public class PolicyResolver {

    private final PolicyRepository policies;

    public PolicyResolver(PolicyRepository policies) {
        this.policies = policies;
    }

    /**
     * Эффективные значения всех правил для (организация, тип ПЛ).
     * organizationRma/waybillType могут быть null — тогда учитываются только более общие уровни.
     *
     * <p>Кэшируется в Redis: результат зависит только от (organizationRma, waybillType) и меняется
     * лишь при изменении политик. Ключ null-безопасно строится из обоих параметров
     * (напр. «*|*», «RMA123|B_INT»). Любая запись политики инвалидирует весь кэш (см. save/delete).</p>
     */
    @Cacheable(cacheNames = CacheConfig.POLICIES_CACHE,
            key = "T(java.util.Objects).toString(#organizationRma,'*') + '|' + T(java.util.Objects).toString(#waybillType,'*')")
    public Map<String, String> effective(String organizationRma, String waybillType) {
        Map<String, String> national = new LinkedHashMap<>();
        Map<String, String> organization = new LinkedHashMap<>();
        Map<String, String> vehicleType = new LinkedHashMap<>();
        for (Policy p : policies.findByEnabledTrue()) {
            switch (p.getScopeLevel()) {
                case "NATIONAL" -> national.put(p.getRuleKey(), p.getRuleValue());
                case "ORGANIZATION" -> {
                    if (organizationRma != null && organizationRma.equals(p.getScopeKey())) {
                        organization.put(p.getRuleKey(), p.getRuleValue());
                    }
                }
                case "VEHICLE_TYPE" -> {
                    if (waybillType != null && waybillType.equals(p.getScopeKey())) {
                        vehicleType.put(p.getRuleKey(), p.getRuleValue());
                    }
                }
                default -> { /* неизвестный уровень игнорируется */ }
            }
        }
        Map<String, String> result = new LinkedHashMap<>(national);
        result.putAll(organization); // организация переопределяет национальный уровень
        result.putAll(vehicleType);  // тип ПЛ переопределяет всё
        return result;
    }

    /**
     * Сохранение политики (create/update). Инвалидируем ВЕСЬ кэш эффективных политик
     * (allEntries=true): одно правило (особенно NATIONAL) влияет на эффективный набор
     * для множества (организация, тип ПЛ), а ключи кэша по этим комбинациям заранее неизвестны —
     * полная очистка проще и гарантирует, что после правки не отдаётся устаревшее.
     */
    @CacheEvict(cacheNames = CacheConfig.POLICIES_CACHE, allEntries = true)
    public Policy save(Policy policy) {
        return policies.save(policy);
    }

    /** Удаление политики — та же полная инвалидация кэша эффективных политик. */
    @CacheEvict(cacheNames = CacheConfig.POLICIES_CACHE, allEntries = true)
    public void delete(Policy policy) {
        policies.delete(policy);
    }
}
