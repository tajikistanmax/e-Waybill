package tj.mintrans.epd.masterdata.service;

import org.springframework.stereotype.Service;
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
     */
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
}
