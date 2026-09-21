package tj.mintrans.epd.waybill.client;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Простой кэш «значение по ключу на TTL» для справочных ответов master-data (маршруты, организации).
 *
 * <p>Зачем: при построении отчётов waybill-service запрашивал список маршрутов по КАЖДОМУ ПЛ
 * (findRoute → GET /dictionaries/routes) и организации по каждому разрезу — сотни вызовов в минуту
 * с одного внутреннего IP, что упиралось в rate-limit master-data (300/мин по IP → 429 → отчёт 500).
 * Справочники меняются редко; минутная давность для отчёта приемлема.</p>
 *
 * <p>Ключ — контекст вызывающего (хэш bearer-токена или «service»): master-data скоупит списки по
 * токену (тенант видит своё), поэтому ответы разных пользователей не смешиваются. Ограничение
 * размера — при переполнении кэш просто сбрасывается (данные восстановимы).</p>
 */
final class TtlCache<V> {

    private record Entry<V>(Instant at, V value) {
    }

    private final Duration ttl;
    private final int maxEntries;
    private final Supplier<Instant> clock;
    private final ConcurrentHashMap<String, Entry<V>> map = new ConcurrentHashMap<>();

    TtlCache(Duration ttl) {
        this(ttl, 1000, Instant::now);
    }

    TtlCache(Duration ttl, int maxEntries, Supplier<Instant> clock) {
        this.ttl = ttl;
        this.maxEntries = maxEntries;
        this.clock = clock;
    }

    /** Значение по ключу: свежее — из кэша, иначе загрузка через {@code loader} и запоминание. */
    V get(String key, Supplier<V> loader) {
        Instant now = clock.get();
        Entry<V> e = map.get(key);
        if (e != null && Duration.between(e.at(), now).compareTo(ttl) < 0) {
            return e.value();
        }
        V v = loader.get();
        if (map.size() >= maxEntries) {
            map.clear();
        }
        map.put(key, new Entry<>(now, v));
        return v;
    }

    void invalidateAll() {
        map.clear();
    }

    int size() {
        return map.size();
    }
}
