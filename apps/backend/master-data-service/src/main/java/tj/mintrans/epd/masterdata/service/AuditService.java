package tj.mintrans.epd.masterdata.service;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tj.mintrans.epd.masterdata.config.CurrentUser;
import tj.mintrans.epd.masterdata.domain.AuditLog;
import tj.mintrans.epd.masterdata.repository.AuditLogRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Запись событий в журнал аудита. Актор берётся из текущего JWT.
 * Сбой записи аудита не должен рушить основную операцию, но обязан быть ЗАМЕТЕН
 * (лог ERROR) — иначе пробел в append-only следе остаётся незамеченным.
 *
 * <p>Каждая запись зашита в hash-chain (ИБ-13.6.3): {@code record_hash = SHA256(prev_hash
 * || поля_записи)}. {@link #record} сериализован через Postgres advisory-lock на время
 * транзакции — без этого конкурентные записи могли бы прочитать один и тот же «последний»
 * хеш и создать разветвление цепочки вместо линейной последовательности.</p>
 */
@Service
public class AuditService {

    public static final String CREATE = "CREATE";
    public static final String UPDATE = "UPDATE";
    public static final String DELETE = "DELETE";
    /** Доступ на чтение особой категории данных (ИБ-13.1.3) — сам факт просмотра, не изменение. */
    public static final String ACCESS = "ACCESS";

    /** Хеш «нулевой» записи — с него начинается цепочка. */
    public static final String GENESIS = "GENESIS";

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository repository;
    private final CurrentUser currentUser;

    public AuditService(AuditLogRepository repository, CurrentUser currentUser) {
        this.repository = repository;
        this.currentUser = currentUser;
    }

    @Transactional
    public void record(String action, String entityType, String entityKey, String oldValue, String newValue) {
        HttpServletRequest request = currentRequest();
        String clientIp = request != null ? trim(clientIp(request), 64) : null;
        String userAgent = request != null ? trim(request.getHeader("User-Agent"), 512) : null;
        recordAs(currentUser.username().orElse("system"), currentUser.organizationRma().orElse(null),
                action, entityType, entityKey, oldValue, newValue, clientIp, userAgent);
    }

    /**
     * Событие входа: актор — тот, кто входит (сессии у него ещё нет, поэтому не из токена),
     * IP и браузер — из текущего запроса. До 24.09.2026 вход писался без IP и браузера, а
     * неудачные попытки не писались вовсе (раньше их присылал Keycloak).
     */
    @Transactional
    public void recordAuth(String actor, String actorOrg, String action, String entityKey, String detail) {
        HttpServletRequest request = currentRequest();
        String clientIp = request != null ? trim(clientIp(request), 64) : null;
        String userAgent = request != null ? trim(request.getHeader("User-Agent"), 512) : null;
        recordAs(trim(actor, 150), actorOrg, action, "AUTH", trim(entityKey, 150), null, detail, clientIp, userAgent);
    }

    /**
     * Вариант для вызовов без HTTP-контекста текущего пользователя (фоновые задачи —
     * например, приём событий входа из Keycloak {@code KeycloakEventAuditSync}, где
     * «актор» записи — не тот, кто вызвал этот метод, а субъект самого события).
     */
    @Transactional
    public void recordAs(String actor, String actorOrg, String action, String entityType, String entityKey,
                         String oldValue, String newValue, String clientIp, String userAgent) {
        try {
            var entry = new AuditLog();
            entry.setActor(actor == null || actor.isBlank() ? "system" : actor);
            entry.setActorOrg(actorOrg);
            entry.setAction(action);
            entry.setEntityType(entityType);
            entry.setEntityKey(entityKey);
            entry.setOldValue(oldValue);
            entry.setNewValue(newValue);
            entry.setClientIp(clientIp);
            entry.setUserAgent(userAgent);
            // Advisory-lock держится до конца транзакции — сериализует read-last-hash +
            // insert между конкурентными вызовами record()/recordAs() (в т.ч. из разных потоков/запросов).
            repository.acquireHashChainLock();
            String prevHash = repository.findTopByOrderBySeqDesc().map(AuditLog::getRecordHash).orElse(GENESIS);
            entry.setPrevHash(prevHash);
            entry.setRecordHash(computeHash(prevHash, entry));
            repository.save(entry);
        } catch (RuntimeException e) {
            // Аудит не должен ломать бизнес-операцию, но потеря записи ДОЛЖНА быть заметна
            // (мониторинг/алерт по ERROR) — иначе изменение остаётся неотслеженным.
            log.error("Не удалось записать аудит: action={} entityType={} entityKey={} — запись потеряна: {}",
                    action, entityType, entityKey, e.toString());
        }
    }

    /** Каноническое представление записи для хеширования — используется и при записи, и при проверке. */
    public static String computeHash(String prevHash, AuditLog e) {
        String canonical = String.join("",
                nullToEmpty(prevHash), nullToEmpty(e.getActor()), nullToEmpty(e.getActorOrg()),
                nullToEmpty(e.getAction()), nullToEmpty(e.getEntityType()), nullToEmpty(e.getEntityKey()),
                nullToEmpty(e.getOldValue()), nullToEmpty(e.getNewValue()));
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha256.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e2) {
            throw new IllegalStateException("SHA-256 недоступен", e2);
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** HTTP-запрос текущего потока или null (внутренний межсервисный вызов без веб-контекста). */
    private static HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs
                ? attrs.getRequest() : null;
    }

    /**
     * IP клиента: за обратным прокси госЦОД реальный адрес в X-Forwarded-For
     * (берём первый — исходный клиент), иначе X-Real-IP, иначе remoteAddr.
     */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        return (realIp != null && !realIp.isBlank()) ? realIp.trim() : request.getRemoteAddr();
    }

    private static String trim(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
