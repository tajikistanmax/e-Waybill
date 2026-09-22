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
    AWAITING_PAYMENT, // услуга платная и не покрыта абонементом
    PAID,             // оплата подтверждена (шлюз/бухгалтер); транзитный к READY
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
            EnumSet.of(CREATED, AWAITING_PAYMENT, PAID, READY, ISSUED, ACTIVE);

    /**
     * Отработанные документы для отчётности: закрытый лист и тот же закрытый лист, убранный
     * в архив по сроку ретенции ({@code LifecycleScheduler}: COMPLETED → ARCHIVED). Для истории
     * перевозок это один и тот же факт выполненной работы.
     *
     * <p>До 22.09.2026 отчёты считали «завершённым» только {@link #COMPLETED}, поэтому любой
     * прошедший период показывал нули: на стенде 2,28 млн перенесённых листов имеют статус
     * ARCHIVED, и сводка за июль 2026 выдавала «52 895 путевых листов, пробег 0» (находка
     * сквозной приёмки, блок F). Везде, где отчёт имеет в виду «лист отработан», используется
     * этот набор, а не одиночный статус.</p>
     */
    public static final Set<WaybillStatus> FINISHED = EnumSet.of(COMPLETED, ARCHIVED);

    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELLED || this == EXPIRED || this == ARCHIVED;
    }
}
