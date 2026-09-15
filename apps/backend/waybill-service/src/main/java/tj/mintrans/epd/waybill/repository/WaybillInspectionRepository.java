package tj.mintrans.epd.waybill.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tj.mintrans.epd.waybill.domain.WaybillInspection;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface WaybillInspectionRepository extends JpaRepository<WaybillInspection, UUID> {

    /** Проверки конкретного путевого листа, новые сверху. */
    List<WaybillInspection> findByWaybillIdOrderByCreatedAtDesc(UUID waybillId);

    /** Проверки за период — журнал дорожного контроля (для отчёта инспектора и надзора). */
    List<WaybillInspection> findByCreatedAtBetweenOrderByCreatedAtDesc(OffsetDateTime from, OffsetDateTime to);

    /** Проверки конкретного инспектора за период — его личный отчёт о работе. */
    List<WaybillInspection> findByInspectorRmaAndCreatedAtBetweenOrderByCreatedAtDesc(
            String inspectorRma, OffsetDateTime from, OffsetDateTime to);
}
