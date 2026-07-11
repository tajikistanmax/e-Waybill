package tj.mintrans.epd.waybill.event;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import tj.mintrans.epd.waybill.service.NotificationService;

/**
 * Слушает переходы статуса ПОСЛЕ commit и создаёт уведомление организации
 * (отдельная транзакция внутри NotificationService). Отдельный компонент от Kafka-моста —
 * независимые получатели одного события.
 */
@Component
public class NotificationEventListener {

    private final NotificationService notifications;

    public NotificationEventListener(NotificationService notifications) {
        this.notifications = notifications;
    }

    @TransactionalEventListener
    public void on(WaybillStatusChanged event) {
        notifications.onStatusChanged(event);
    }
}
