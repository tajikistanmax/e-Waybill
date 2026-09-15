package tj.mintrans.epd.masterdata.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tj.mintrans.epd.masterdata.client.KeycloakAdminClient;
import tj.mintrans.epd.masterdata.repository.AuditLogRepository;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * Приём событий входа/выхода Keycloak (ИБ-13.6.1: «входы и отказы аутентификации» —
 * единственный пункт списка аудита, которого не было в audit_log) в единый журнал.
 *
 * <p>Опрос вместо push-уведомлений (Keycloak event listener SPI) — сознательный выбор:
 * SPI требует отдельного плагина, компилируемого и монтируемого в образ Keycloak,
 * это отдельная задача. Опрос {@code GET /admin/realms/epd/events} даёт тот же результат
 * без изменения образа Keycloak, ценой задержки до {@link #POLL_MS} между входом и записью
 * в аудит — приемлемо для журнала (не для авторизации, которая и так уже применена
 * до этого момента самим Keycloak).</p>
 *
 * <p>Водяная метка — не отдельная таблица, а {@code max(occurred_at)} среди уже
 * принятых записей {@code entityType=AUTH} в самом audit_log: переживает рестарт
 * без дополнительного состояния, и естественно не задваивает записи.</p>
 */
@Component
public class KeycloakEventAuditSync {

    private static final Logger log = LoggerFactory.getLogger(KeycloakEventAuditSync.class);
    private static final long POLL_MS = 60_000;
    private static final int FETCH_MAX = 100;

    private final KeycloakAdminClient keycloak;
    private final AuditLogRepository auditLogRepository;
    private final AuditService auditService;

    /** Момент старта сервиса — нижняя граница при пустом журнале (не тянем историю до сегодня). */
    private final long startedAtMillis = System.currentTimeMillis();

    public KeycloakEventAuditSync(KeycloakAdminClient keycloak, AuditLogRepository auditLogRepository,
                                  AuditService auditService) {
        this.keycloak = keycloak;
        this.auditLogRepository = auditLogRepository;
        this.auditService = auditService;
    }

    @Scheduled(fixedDelay = POLL_MS, initialDelay = 15_000)
    public void sync() {
        if (!keycloak.isEnabled()) {
            return;
        }
        try {
            long watermark = currentWatermarkMillis();
            List<KeycloakAdminClient.AuthEvent> events = keycloak.recentAuthEvents(FETCH_MAX);
            events.stream()
                    .filter(e -> e.timeMillis() > watermark)
                    .sorted(Comparator.comparingLong(KeycloakAdminClient.AuthEvent::timeMillis))
                    .forEach(this::ingest);
        } catch (RuntimeException e) {
            // Как и любая запись аудита: сбой опроса не должен ронять сервис, но обязан
            // быть заметен — иначе пробел в приёме событий входа останется незамеченным.
            log.error("KeycloakEventAuditSync: не удалось получить/обработать события Keycloak: {}", e.toString());
        }
    }

    private long currentWatermarkMillis() {
        var page = auditLogRepository.findByEntityTypeOrderByOccurredAtDesc("AUTH", PageRequest.of(0, 1));
        return page.hasContent() ? page.getContent().get(0).getOccurredAt().toInstant().toEpochMilli() : startedAtMillis;
    }

    private void ingest(KeycloakAdminClient.AuthEvent e) {
        String action = switch (e.type()) {
            case "LOGIN" -> "LOGIN";
            case "LOGIN_ERROR" -> "LOGIN_ERROR";
            case "LOGOUT" -> "LOGOUT";
            case "LOGOUT_ERROR" -> "LOGOUT_ERROR";
            default -> e.type();
        };
        String detail = e.error() == null || e.error().isBlank() ? null : "error=" + e.error();
        auditService.recordAs(e.username(), null, action, "AUTH", e.clientId(), null, detail, e.ipAddress(), null);
        log.debug("KeycloakEventAuditSync: принято событие {} user={} time={}",
                e.type(), e.username(), Instant.ofEpochMilli(e.timeMillis()));
    }
}
