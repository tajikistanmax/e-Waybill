package tj.mintrans.epd.waybill.calc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tj.mintrans.epd.waybill.calc.model.BrandNorms;
import tj.mintrans.epd.waybill.client.MasterDataClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Резолвер нормативов расхода топлива марки ТС ({@link BrandNorms}) по названию марки —
 * через {@link MasterDataClient} ({@code /api/v1/legacy-ref/brands?name=...}).
 *
 * <p>Снимок ПЛ хранит имя марки, а не id, поэтому поиск по имени (регистронезависимо).
 * Результат кэшируется на {@link #TTL}; недоступность master-data → {@code null}
 * (расчёт применяет нейтральные значения).</p>
 */
@Component
public class BrandNormsProvider {

    private static final Logger log = LoggerFactory.getLogger(BrandNormsProvider.class);
    private static final Duration TTL = Duration.ofMinutes(5);

    private final MasterDataClient masterData;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public BrandNormsProvider(MasterDataClient masterData) {
        this.masterData = masterData;
    }

    private record Cached(Instant at, BrandNorms value) {
    }

    /**
     * Нормативы марки по названию.
     *
     * @param brandName название марки ({@code null}/пусто → {@code null})
     * @return {@link BrandNorms} либо {@code null}, если марка не найдена / master-data недоступен
     */
    public BrandNorms forName(String brandName) {
        if (brandName == null || brandName.isBlank()) {
            return null;
        }
        String key = brandName.trim().toLowerCase();
        Cached cached = cache.get(key);
        if (cached != null && Duration.between(cached.at(), Instant.now()).compareTo(TTL) < 0) {
            return cached.value();
        }
        BrandNorms resolved = load(brandName);
        cache.put(key, new Cached(Instant.now(), resolved));
        return resolved;
    }

    private BrandNorms load(String brandName) {
        try {
            Optional<Map<String, Object>> row = masterData.findBrandByName(brandName);
            if (row.isEmpty()) {
                log.warn("Марка «{}» не найдена в справочнике — нормативы расхода не применены", brandName);
                return null;
            }
            Map<String, Object> m = row.get();
            return new BrandNorms(
                    asLong(m.get("id")),
                    asString(m.get("fuel100")),
                    asString(m.get("fuel100Dushanbe")),
                    asString(m.get("fuelHour")),
                    asDouble(m.get("fuelInteriorHeating")));
        } catch (RuntimeException e) {
            log.warn("Справочник марок недоступен ({}) — нормативы расхода не применены", e.toString());
            return null;
        }
    }

    private static Long asLong(Object v) {
        if (v == null) {
            return null;
        }
        return v instanceof Number n ? n.longValue() : Long.valueOf(v.toString());
    }

    private static String asString(Object v) {
        return v == null ? null : v.toString();
    }

    private static double asDouble(Object v) {
        if (v == null) {
            return 0d;
        }
        return v instanceof Number n ? n.doubleValue() : Double.parseDouble(v.toString());
    }
}
