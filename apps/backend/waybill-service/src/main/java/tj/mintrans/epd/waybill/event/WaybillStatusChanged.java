package tj.mintrans.epd.waybill.event;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Доменное событие: переход путевого листа в новый статус.
 * Публикуется в Kafka (topic epd.waybill.status) ПОСЛЕ фиксации транзакции —
 * его читают единый личный кабинет Минтранса, аналитика и антифрод.
 */
public record WaybillStatusChanged(
        UUID waybillId,
        String number,
        String waybillType,
        String organizationRma,
        String vehicleRegNumber,
        String driverRma,
        String fromStatus,
        String toStatus,
        String actor,
        String reason,
        String source,
        OffsetDateTime occurredAt) {
}
