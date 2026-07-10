package tj.mintrans.epd.waybill.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Статусная модель путевого листа (spec/data/waybill-statuses.yaml).
 * Медосмотр и техконтроль идут параллельно: их результат отражают флаги
 * medPassed/techPassed на документе, статус — агрегированное состояние.
 */
public enum WaybillStatus {
    DRAFT,
    CREATED,          // Т1 подписан; ожидает медосмотра и техконтроля
    MED_REJECTED,
    TECH_REJECTED,
    AWAITING_PAYMENT,
    READY,            // Т2+Т3 (+оплата); присвоен номер и QR
    ISSUED,           // водитель подтвердил получение
    ACTIVE,           // Т4: на линии
    RETURNED,         // Т5: одометр возврата
    COMPLETED,        // Т6/закрыт
    CANCELLED,
    EXPIRED,
    BLOCKED,
    ARCHIVED;

    /** Статусы, при которых ПЛ считается «действующим» — блокирует новый ПЛ на то же ТС/водителя. */
    public static final Set<WaybillStatus> OPEN_STATUSES =
            EnumSet.of(CREATED, AWAITING_PAYMENT, READY, ISSUED, ACTIVE);

    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELLED || this == EXPIRED || this == ARCHIVED;
    }
}
