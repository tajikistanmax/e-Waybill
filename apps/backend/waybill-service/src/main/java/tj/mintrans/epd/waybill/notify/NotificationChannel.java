package tj.mintrans.epd.waybill.notify;

import tj.mintrans.epd.waybill.domain.Notification;

/**
 * Канал доставки уведомления. In-app хранится в БД всегда; внешние каналы
 * (SMS/push/Telegram/e-mail) подключаются как отдельные @Component-реализации
 * этого интерфейса — NotificationService вызывает все зарегистрированные каналы.
 * Так внешняя доставка добавляется без изменения бизнес-логики (нужны провайдеры).
 */
public interface NotificationChannel {

    /** Отправить уведомление во внешний канал. Реализация — best-effort. */
    void send(Notification notification);
}
