package tj.mintrans.epd.waybill.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import tj.mintrans.epd.waybill.domain.VerifyScanLog;

import java.util.List;
import java.util.UUID;

public interface VerifyScanLogRepository extends JpaRepository<VerifyScanLog, UUID> {

    /** История проверок конкретного ПЛ по номеру, новые сверху. */
    List<VerifyScanLog> findByWaybillNumberOrderByScannedAtDesc(String waybillNumber, Pageable pageable);

    /** Глобальная лента последних проверок, новые сверху. */
    List<VerifyScanLog> findAllByOrderByScannedAtDesc(Pageable pageable);
}
