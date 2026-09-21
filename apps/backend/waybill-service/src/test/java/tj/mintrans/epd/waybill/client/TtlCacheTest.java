package tj.mintrans.epd.waybill.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Кэш справочных ответов master-data (маршруты/организации по ключу вызывающего, TTL 60 с). */
class TtlCacheTest {

    @Test
    @DisplayName("в пределах TTL загрузчик вызывается один раз на ключ; после TTL — перезагрузка")
    void cachesWithinTtl() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-21T10:00:00Z"));
        TtlCache<String> cache = new TtlCache<>(Duration.ofSeconds(60), 1000, now::get);
        AtomicInteger loads = new AtomicInteger();

        assertThat(cache.get("user-a", () -> "v" + loads.incrementAndGet())).isEqualTo("v1");
        assertThat(cache.get("user-a", () -> "v" + loads.incrementAndGet())).isEqualTo("v1");
        now.set(now.get().plusSeconds(59));
        assertThat(cache.get("user-a", () -> "v" + loads.incrementAndGet())).isEqualTo("v1");
        now.set(now.get().plusSeconds(2));
        assertThat(cache.get("user-a", () -> "v" + loads.incrementAndGet())).isEqualTo("v2");
        assertThat(loads.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("разные ключи (разные токены/тенанты) не смешиваются")
    void keysAreIsolated() {
        TtlCache<String> cache = new TtlCache<>(Duration.ofSeconds(60));
        assertThat(cache.get("tenant-1", () -> "routes-1")).isEqualTo("routes-1");
        assertThat(cache.get("tenant-2", () -> "routes-2")).isEqualTo("routes-2");
        assertThat(cache.get("tenant-1", () -> "STALE")).isEqualTo("routes-1");
        assertThat(cache.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("переполнение — сброс, invalidateAll — сброс")
    void boundedAndInvalidate() {
        TtlCache<Integer> cache = new TtlCache<>(Duration.ofSeconds(60), 2, Instant::now);
        cache.get("a", () -> 1);
        cache.get("b", () -> 2);
        cache.get("c", () -> 3);          // size >= max → clear, затем put c
        assertThat(cache.size()).isEqualTo(1);
        cache.invalidateAll();
        assertThat(cache.size()).isZero();
    }
}
