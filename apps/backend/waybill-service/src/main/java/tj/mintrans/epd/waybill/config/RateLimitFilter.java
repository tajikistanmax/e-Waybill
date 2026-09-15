package tj.mintrans.epd.waybill.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Базовое лимитирование запросов по IP (ИБ-13.5.3): fixed-window счётчик в памяти
 * одного узла — сознательно простое решение, закрывающее «нет вообще никакой защиты»,
 * а не полноценная многоуровневая система (per-user/per-client/per-endpoint, Redis-backed
 * для нескольких реплик). При масштабировании на несколько реплик заменить на общий
 * счётчик (Redis, уже есть в compose) — иначе каждая реплика лимитирует независимо.
 *
 * Публичные без-токенные эндпоинты (QR verify, JWKS) получают более строгий потолок,
 * чем общий трафик — это единственная поверхность, доступная совсем без аутентификации.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final List<String> PUBLIC_PREFIXES = List.of("/api/v1/verify/", "/.well-known/");
    private static final int SWEEP_EVERY_N_REQUESTS = 2000;

    private final boolean enabled;
    private final long windowMillis;
    private final int generalCapacity;
    private final int publicCapacity;
    private final ConcurrentHashMap<String, Window> counters = new ConcurrentHashMap<>();
    private final AtomicLong requestCounter = new AtomicLong();

    public RateLimitFilter(
            @Value("${epd.ratelimit.enabled:true}") boolean enabled,
            @Value("${epd.ratelimit.window-seconds:60}") long windowSeconds,
            @Value("${epd.ratelimit.general-capacity:300}") int generalCapacity,
            @Value("${epd.ratelimit.public-capacity:60}") int publicCapacity) {
        this.enabled = enabled;
        this.windowMillis = windowSeconds * 1000L;
        this.generalCapacity = generalCapacity;
        this.publicCapacity = publicCapacity;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!enabled) {
            chain.doFilter(request, response);
            return;
        }
        boolean isPublic = isPublicPath(request.getRequestURI());
        int capacity = isPublic ? publicCapacity : generalCapacity;
        String key = clientIp(request) + (isPublic ? ":pub" : ":gen");
        long now = System.currentTimeMillis();

        if (requestCounter.incrementAndGet() % SWEEP_EVERY_N_REQUESTS == 0) {
            sweep(now);
        }

        Window w = counters.compute(key, (k, existing) -> {
            if (existing == null || now - existing.windowStart() >= windowMillis) {
                return new Window(now, new AtomicInteger(1));
            }
            existing.count().incrementAndGet();
            return existing;
        });

        if (w.count().get() > capacity) {
            long retryAfterSec = Math.max(1, (windowMillis - (now - w.windowStart())) / 1000);
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(retryAfterSec));
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"too_many_requests\",\"retryAfterSeconds\":" + retryAfterSec + "}");
            log.warn("Rate limit: ip={} path={} count={} capacity={}", clientIp(request), request.getRequestURI(), w.count().get(), capacity);
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean isPublicPath(String uri) {
        return PUBLIC_PREFIXES.stream().anyMatch(uri::startsWith);
    }

    /**
     * {@code entrySet().removeIf(...)} у {@link ConcurrentHashMap} удаляет запись по ключу
     * БЕЗ сверки, что значение не изменилось с момента, когда предикат его увидел (итератор
     * зовёт {@code replaceNode(key, null, null)} — безусловное удаление). Узкая гонка (найдена
     * УАТ 2026-09-04, код-ревью, не воспроизведена вживую): между чтением устаревшего окна и
     * физическим удалением другой поток мог через {@code compute()} создать для того же ключа
     * свежее окно — sweep стёр бы и его, дав на один запрос больше в пределах текущего окна
     * (не обход лимита, не факт эксплуатации, но исправление тривиально). CAS-вариант через
     * двухаргументный {@code remove(key, value)} удаляет только если значение не изменилось.
     */
    private void sweep(long now) {
        for (var e : counters.entrySet()) {
            Window w = e.getValue();
            if (now - w.windowStart() >= windowMillis * 2) {
                counters.remove(e.getKey(), w);
            }
        }
    }

    /** IP клиента: за обратным прокси госЦОД — X-Forwarded-For, иначе X-Real-IP, иначе remoteAddr. */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        return (realIp != null && !realIp.isBlank()) ? realIp.trim() : request.getRemoteAddr();
    }

    private record Window(long windowStart, AtomicInteger count) {
    }
}
