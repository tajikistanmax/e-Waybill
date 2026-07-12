package tj.mintrans.epd.masterdata.service;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tj.mintrans.epd.masterdata.config.CurrentUser;
import tj.mintrans.epd.masterdata.domain.AuditLog;
import tj.mintrans.epd.masterdata.repository.AuditLogRepository;

/**
 * Запись событий в журнал аудита. Актор берётся из текущего JWT.
 * Сбой записи аудита не должен рушить основную операцию, но обязан быть ЗАМЕТЕН
 * (лог ERROR) — иначе пробел в append-only следе остаётся незамеченным.
 */
@Service
public class AuditService {

    public static final String CREATE = "CREATE";
    public static final String UPDATE = "UPDATE";
    public static final String DELETE = "DELETE";

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository repository;
    private final CurrentUser currentUser;

    public AuditService(AuditLogRepository repository, CurrentUser currentUser) {
        this.repository = repository;
        this.currentUser = currentUser;
    }

    public void record(String action, String entityType, String entityKey, String oldValue, String newValue) {
        try {
            var entry = new AuditLog();
            entry.setActor(currentUser.username().orElse("system"));
            entry.setActorOrg(currentUser.organizationRma().orElse(null));
            entry.setAction(action);
            entry.setEntityType(entityType);
            entry.setEntityKey(entityKey);
            entry.setOldValue(oldValue);
            entry.setNewValue(newValue);
            HttpServletRequest request = currentRequest();
            if (request != null) {
                entry.setClientIp(trim(clientIp(request), 64));
                entry.setUserAgent(trim(request.getHeader("User-Agent"), 512));
            }
            repository.save(entry);
        } catch (RuntimeException e) {
            // Аудит не должен ломать бизнес-операцию, но потеря записи ДОЛЖНА быть заметна
            // (мониторинг/алерт по ERROR) — иначе изменение остаётся неотслеженным.
            log.error("Не удалось записать аудит: action={} entityType={} entityKey={} — запись потеряна: {}",
                    action, entityType, entityKey, e.toString());
        }
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
