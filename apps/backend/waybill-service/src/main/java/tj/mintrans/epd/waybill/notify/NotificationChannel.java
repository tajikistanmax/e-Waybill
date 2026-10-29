package tj.mintrans.epd.waybill.notify;

import tj.mintrans.epd.waybill.domain.Notification;

/**
 * Канал доставки уведомления. In-app хранится в БД всегда — это единственный канал
 * платформы (решение владельца: внешние каналы типа SMS не нужны и не планируются).
 * Интерфейс оставлен как точка расширения на будущее, если это решение изменится.
 */
public interface NotificationChannel {

    /** Отправить уведомление во внешний канал. Реализация — best-effort. */
    void send(Notification notification);
}
