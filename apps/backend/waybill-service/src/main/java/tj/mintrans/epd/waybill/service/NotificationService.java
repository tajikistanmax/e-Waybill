package tj.mintrans.epd.waybill.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tj.mintrans.epd.waybill.config.CurrentUser;
import tj.mintrans.epd.waybill.domain.Notification;
import tj.mintrans.epd.waybill.event.WaybillStatusChanged;
import tj.mintrans.epd.waybill.notify.NotificationChannel;
import tj.mintrans.epd.waybill.repository.NotificationRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Уведомления организации о событиях путевого листа. Генерация — после commit
 * из события WaybillStatusChanged (в отдельной транзакции; сбой не влияет на переход).
 */
@Service
public class NotificationService {

    /** Статусы, о которых уведомляем организацию, и заголовок уведомления. */
    private static final Map<String, String> NOTEWORTHY = Map.of(
            "MED_REJECTED", "Водитель не допущен по медосмотру",
            "TECH_REJECTED", "ТС не прошло технический контроль",
            "READY", "Путевой лист готов к выдаче",
            "BLOCKED", "Путевой лист заблокирован инспектором",
            "EXPIRED", "Путевой лист просрочен");

    private final NotificationRepository repository;
    private final CurrentUser currentUser;
    private final List<NotificationChannel> channels;

    public NotificationService(NotificationRepository repository, CurrentUser currentUser,
                               List<NotificationChannel> channels) {
        this.repository = repository;
        this.currentUser = currentUser;
        this.channels = channels;
    }

    /** Создать уведомление по событию перехода (новая транзакция; best-effort). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onStatusChanged(WaybillStatusChanged event) {
        String title = NOTEWORTHY.get(event.toStatus());
        if (title == null || event.organizationRma() == null) {
            return;
        }
        try {
            var n = new Notification();
            n.setRecipientRma(event.organizationRma());
            n.setWaybillId(event.waybillId());
            n.setKind(event.toStatus());
            n.setTitle(title);
            String number = event.number() != null ? event.number() : "б/н";
            String body = "Путевой лист № " + number;
            if (event.reason() != null && !event.reason().isBlank()) {
                body += ". " + event.reason();
            }
            n.setBody(body);
            repository.save(n);
            // Доставка во внешние каналы (SMS/push/…), если подключены. Каждый — best-effort.
            for (NotificationChannel channel : channels) {
                try { channel.send(n); } catch (RuntimeException ignored) { /* канал не должен рушить */ }
            }
        } catch (RuntimeException ex) {
            // Уведомление best-effort: сбой записи не должен влиять на бизнес-поток.
        }
    }

    // -------------------------------------------------------- чтение (тенант-скоуп)

    public List<Notification> list() {
        return currentUser.organizationRma()
                .map(repository::findTop100ByRecipientRmaOrderByCreatedAtDesc)
                .orElseGet(repository::findTop100ByOrderByCreatedAtDesc);
    }

    public long unreadCount() {
        return currentUser.organizationRma()
                .map(repository::countByRecipientRmaAndReadAtIsNull)
                .orElseGet(repository::countByReadAtIsNull);
    }

    @Transactional
    public void markRead(UUID id) {
        repository.findById(id).ifPresent(n -> {
            boolean own = currentUser.organizationRma()
                    .map(rma -> rma.equals(n.getRecipientRma())).orElse(true); // platform-admin — без ограничения
            if (own && n.getReadAt() == null) {
                n.setReadAt(OffsetDateTime.now());
                repository.save(n);
            }
        });
    }

    @Transactional
    public void markAllRead() {
        currentUser.organizationRma().ifPresent(rma ->
                repository.findByRecipientRmaAndReadAtIsNull(rma).forEach(n -> {
                    n.setReadAt(OffsetDateTime.now());
                    repository.save(n);
                }));
    }
}
