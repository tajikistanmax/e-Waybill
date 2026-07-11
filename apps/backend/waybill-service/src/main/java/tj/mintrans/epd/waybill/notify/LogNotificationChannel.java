package tj.mintrans.epd.waybill.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tj.mintrans.epd.waybill.domain.Notification;

/**
 * Канал по умолчанию (dev): записывает уведомление в лог сервиса. Реальные каналы
 * (SMS-шлюз «Корти милли»/операторов, push, Telegram) добавляются отдельными
 * @Component-реализациями {@link NotificationChannel} при наличии провайдеров.
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
