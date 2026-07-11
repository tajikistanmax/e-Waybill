package tj.mintrans.epd.masterdata.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
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
            repository.save(entry);
        } catch (RuntimeException e) {
            // Аудит не должен ломать бизнес-операцию, но потеря записи ДОЛЖНА быть заметна
            // (мониторинг/алерт по ERROR) — иначе изменение остаётся неотслеженным.
            log.error("Не удалось записать аудит: action={} entityType={} entityKey={} — запись потеряна: {}",
                    action, entityType, entityKey, e.toString());
        }
    }
}
