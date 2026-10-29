package tj.mintrans.epd.waybill.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tj.mintrans.epd.waybill.domain.Notification;

/**
 * Единственный канал доставки уведомлений — записывает в лог сервиса (in-app
 * уведомление пишется отдельно, в БД, см. {@code NotificationService}). Внешние
 * каналы (SMS и т.п.) не нужны и не планируются — решение владельца.
 */
@Component
public class LogNotificationChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(LogNotificationChannel.class);

    @Override
    public void send(Notification n) {
        log.info("Уведомление [{}] организации {}: {} (ПЛ {})",
                n.getKind(), n.getRecipientRma(), n.getTitle(), n.getWaybillId());
    }
}
