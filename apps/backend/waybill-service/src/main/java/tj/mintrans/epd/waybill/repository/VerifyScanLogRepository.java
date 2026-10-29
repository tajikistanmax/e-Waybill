package tj.mintrans.epd.waybill.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import tj.mintrans.epd.waybill.domain.VerifyScanLog;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface VerifyScanLogRepository extends JpaRepository<VerifyScanLog, UUID> {

    /** История проверок конкретного ПЛ по номеру, новые сверху. */
    List<VerifyScanLog> findByWaybillNumberOrderByScannedAtDesc(String waybillNumber, Pageable pageable);

    /** Глобальная лента последних проверок, новые сверху. */
    List<VerifyScanLog> findAllByOrderByScannedAtDesc(Pageable pageable);

    /**
     * Удаляет записи старше {@code cutoff}. Используется только планировщиком ретенции
     * ({@code VerifyScanLogRetentionScheduler}), который сам по себе выключен, пока срок
     * хранения не определён продуктовым/юридическим решением (go-live чек-лист, п. 4.4).
     */
    @Modifying
    long deleteByScannedAtBefore(OffsetDateTime cutoff);
}
