package tj.mintrans.epd.masterdata.service;

import org.springframework.stereotype.Service;
import tj.mintrans.epd.masterdata.config.CurrentUser;
import tj.mintrans.epd.masterdata.domain.AuditLog;
import tj.mintrans.epd.masterdata.repository.AuditLogRepository;

/**
 * Запись событий в журнал аудита. Актор берётся из текущего JWT.
 * Сбой записи аудита не должен рушить основную операцию — глушится.
 */
@Service
public class AuditService {

    public static final String CREATE = "CREATE";
    public static final String UPDATE = "UPDATE";
    public static final String DELETE = "DELETE";

    private final AuditLogRepository repository;
    private final CurrentUser currentUser;

    public AuditService(AuditLogRepository repository, CurrentUser currentUser) {
        this.repository = repository;
        this.currentUser = currentUser;
    }

    public void record(String action, String entityType, String entityKey, String oldValue, String newValue) {
        try {
            var log = new AuditLog();
            log.setActor(currentUser.username().orElse("system"));
            log.setActorOrg(currentUser.organizationRma().orElse(null));
            log.setAction(action);
            log.setEntityType(entityType);
            log.setEntityKey(entityKey);
            log.setOldValue(oldValue);
            log.setNewValue(newValue);
            repository.save(log);
        } catch (RuntimeException e) {
            // Аудит не должен ломать бизнес-операцию: молча игнорируем сбой записи.
        }
    }
}
