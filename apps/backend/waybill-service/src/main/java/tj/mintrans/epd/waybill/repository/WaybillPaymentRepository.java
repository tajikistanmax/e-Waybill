package tj.mintrans.epd.waybill.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import tj.mintrans.epd.waybill.domain.WaybillPayment;

import java.util.Optional;
import java.util.UUID;

public interface WaybillPaymentRepository extends JpaRepository<WaybillPayment, UUID> {

    Optional<WaybillPayment> findByWaybillId(UUID waybillId);

    /**
     * Загрузка записи оплаты под пессимистичной блокировкой строки — для идемпотентности
     * при ОДНОВРЕМЕННЫХ доставках вебхука (шлюзы ретраят at-least-once): второй параллельный
     * confirmPayment блокируется, после коммита первого перечитывает статус CONFIRMED → 409.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from WaybillPayment p where p.waybillId = :waybillId")
    Optional<WaybillPayment> findByWaybillIdForUpdate(UUID waybillId);
}
