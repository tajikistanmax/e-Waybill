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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Лимитирование частоты запросов (ИБ-13.5.3): счётчики фиксированного окна в памяти узла.
 *
 * <p>Считаем по ДВУМ ключам сразу:</p>
 * <ul>
 *   <li><b>по пользователю</b> — основной предел ({@code general-capacity}). Так предприятие
 *       за одним внешним адресом не делит лимит между всеми сотрудниками: до 22.09.2026 счёт
 *       шёл только по адресу, и 300 запросов в минуту на весь узел означали примерно 30
 *       открытий страницы в минуту на ВСЮ организацию за NAT (находка сквозной приёмки,
 *       блок A5). Ключ — субъект токена; токен не проверяется (это делает фильтр
 *       аутентификации дальше по цепочке), разбор нужен только для раскладки по корзинам;</li>
 *   <li><b>по адресу</b> — внешний предел ({@code ip-capacity}, по умолчанию в 10 раз выше).
 *       Он остаётся защитой от потока с поддельными токенами: подделать субъект можно, но
 *       адрес — нет.</li>
 * </ul>
 *
 * <p>Публичные без-токенные пути (проверка по QR, JWKS) получают отдельный,
 * более строгий потолок по адресу — это единственная поверхность без аутентификации.</p>
 *
 * <p>Число корзин пользователей ограничено {@code max-user-keys}: поток с уникальными
 * поддельными токенами иначе раздувал бы таблицу счётчиков. При переполнении новые
 * пользовательские корзины не заводятся, и запрос ограничивается только пределом по адресу.</p>
 *
 * <p>Счётчик живёт в памяти одного узла. При нескольких репликах заменить на общий
 * (Redis уже есть в compose) — иначе каждая реплика лимитирует независимо.</p>
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final List<String> PUBLIC_PREFIXES = List.of(
            "/api/v1/verify/", "/.well-known/");
    private static final int SWEEP_EVERY_N_REQUESTS = 2000;
    private static final String BEARER = "Bearer ";

    private final boolean enabled;
    private final long windowMillis;
    private final int generalCapacity;
    private final int publicCapacity;
    private final int ipCapacity;
    private final int maxUserKeys;
    private final int integratorCapacity;
    private final ConcurrentHashMap<String, Window> ipCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Window> userCounters = new ConcurrentHashMap<>();
    private final AtomicLong requestCounter = new AtomicLong();

    /** Для тестов: предел служебных учёток равен пределу пользователя. */
    public RateLimitFilter(boolean enabled, long windowSeconds, int generalCapacity, int publicCapacity,
                           int ipCapacity, int maxUserKeys) {
        this(enabled, windowSeconds, generalCapacity, publicCapacity, ipCapacity, maxUserKeys, generalCapacity);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RateLimitFilter(
            @Value("${epd.ratelimit.enabled:true}") boolean enabled,
            @Value("${epd.ratelimit.window-seconds:60}") long windowSeconds,
            @Value("${epd.ratelimit.general-capacity:300}") int generalCapacity,
            @Value("${epd.ratelimit.public-capacity:60}") int publicCapacity,
            @Value("${epd.ratelimit.ip-capacity:3000}") int ipCapacity,
            @Value("${epd.ratelimit.max-user-keys:50000}") int maxUserKeys,
            // Служебные учётки (роль API_INTEGRATOR): внешний агрегатор грузит путевые листы
            // пачками одной учёткой (канал /api/v1/aggregator). Предел человека (300 в минуту) ему
            // мал (нагрузочный тест 24.09.2026). Внешней границей остаётся предел по адресу.
            @Value("${epd.ratelimit.integrator-capacity:3000}") int integratorCapacity) {
        this.enabled = enabled;
        this.windowMillis = windowSeconds * 1000L;
        this.generalCapacity = generalCapacity;
        this.publicCapacity = publicCapacity;
        this.ipCapacity = ipCapacity;
        this.maxUserKeys = maxUserKeys;
        this.integratorCapacity = integratorCapacity;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!enabled) {
            chain.doFilter(request, response);
            return;
        }
        long now = System.currentTimeMillis();
        if (requestCounter.incrementAndGet() % SWEEP_EVERY_N_REQUESTS == 0) {
            sweep(now);
        }

        String ip = clientIp(request);
        boolean isPublic = isPublicPath(request.getRequestURI());

        // Предел по адресу: для публичных путей строгий, для остальных — внешняя граница.
        Window ipWindow = hit(ipCounters, ip + (isPublic ? ":pub" : ":ip"), now);
        int ipLimit = isPublic ? publicCapacity : ipCapacity;
        if (ipWindow.count().get() > ipLimit) {
            reject(response, request, now, ipWindow, ipLimit, "ip=" + ip);
            return;
        }

        if (!isPublic) {
            String subject = subjectKey(request);
            if (subject != null && (userCounters.size() < maxUserKeys || userCounters.containsKey(subject))) {
                Window userWindow = hit(userCounters, subject, now);
                int userLimit = isIntegrator(request) ? integratorCapacity : generalCapacity;
                if (userWindow.count().get() > userLimit) {
                    reject(response, request, now, userWindow, userLimit, "user");
                    return;
                }
            }
        }
        chain.doFilter(request, response);
    }

    private Window hit(ConcurrentHashMap<String, Window> counters, String key, long now) {
        return counters.compute(key, (k, existing) -> {
            if (existing == null || now - existing.windowStart() >= windowMillis) {
                return new Window(now, new AtomicInteger(1));
            }
            existing.count().incrementAndGet();
            return existing;
        });
    }

    private void reject(HttpServletResponse response, HttpServletRequest request,
                        long now, Window w, int capacity, String who) throws IOException {
        long retryAfterSec = Math.max(1, (windowMillis - (now - w.windowStart())) / 1000);
        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf(retryAfterSec));
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"too_many_requests\",\"retryAfterSeconds\":" + retryAfterSec + "}");
        log.warn("Rate limit: {} path={} count={} capacity={}", who, request.getRequestURI(), w.count().get(), capacity);
    }

    /**
     * Токен служебной учётки (роль API_INTEGRATOR). Подпись, как и в {@link #subjectKey}, здесь
     * не проверяется: поддельный токен всё равно отвергнет аутентификация, а поток подделок
     * ограничен пределом по адресу.
     */
    static boolean isIntegrator(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER)) {
            return false;
        }
        String[] parts = header.substring(BEARER.length()).trim().split("\\.");
        if (parts.length != 3) {
            return false;
        }
        try {
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            return payload.contains("\"API_INTEGRATOR\"");
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Ключ корзины пользователя: субъект ({@code sub}) из полезной нагрузки токена, иначе
     * отпечаток самого токена. Подпись НЕ проверяется — токен проверит фильтр аутентификации;
     * здесь значение нужно только для раскладки счётчиков, а подделку субъекта ограничивает
     * внешний предел по адресу.
     */
    static String subjectKey(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER)) {
            return null;
        }
        String token = header.substring(BEARER.length()).trim();
        if (token.isEmpty()) {
            return null;
        }
        String[] parts = token.split("\\.");
        if (parts.length == 3) {
            try {
                String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
                String sub = jsonString(payload, "sub");
                if (sub != null && !sub.isBlank()) {
                    return "sub:" + sub;
                }
            } catch (RuntimeException ignored) {
                // непарсимый токен — падать обратно на отпечаток
            }
        }
        return "tok:" + fingerprint(token);
    }

    /** Значение строкового поля верхнего уровня JSON без подключения парсера. */
    private static String jsonString(String json, String field) {
        String needle = "\"" + field + "\"";
        int at = json.indexOf(needle);
        if (at < 0) {
            return null;
        }
        int colon = json.indexOf(':', at + needle.length());
        if (colon < 0) {
            return null;
        }
        int start = json.indexOf('"', colon + 1);
        if (start < 0) {
            return null;
        }
        int end = json.indexOf('"', start + 1);
        return end < 0 ? null : json.substring(start + 1, end);
    }

    private static String fingerprint(String token) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash).substring(0, 22);
        } catch (Exception e) {
            return Integer.toHexString(token.hashCode());
        }
    }

    private static boolean isPublicPath(String uri) {
        return PUBLIC_PREFIXES.stream().anyMatch(uri::startsWith);
    }

    /**
     * {@code entrySet().removeIf(...)} у {@link ConcurrentHashMap} удаляет запись по ключу
     * БЕЗ сверки, что значение не изменилось с момента, когда предикат его увидел. Узкая гонка
     * (код-ревью УАТ 2026-09-04): между чтением устаревшего окна и удалением другой поток мог
     * через {@code compute()} создать для того же ключа свежее окно — sweep стёр бы и его, дав
     * на один запрос больше. Двухаргументный {@code remove(key, value)} удаляет только если
     * значение не изменилось.
     */
    private void sweep(long now) {
        sweep(ipCounters, now);
        sweep(userCounters, now);
    }

    private void sweep(ConcurrentHashMap<String, Window> counters, long now) {
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
