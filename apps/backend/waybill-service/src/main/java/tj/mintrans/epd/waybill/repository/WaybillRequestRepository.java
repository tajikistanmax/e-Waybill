package tj.mintrans.epd.waybill.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tj.mintrans.epd.waybill.domain.WaybillRequest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WaybillRequestRepository extends JpaRepository<WaybillRequest, UUID> {

    List<WaybillRequest> findByOrganizationRmaOrderByCreatedAtDesc(String organizationRma);

    List<WaybillRequest> findByOrganizationRmaAndStatusOrderByCreatedAtAsc(String organizationRma, String status);

    List<WaybillRequest> findByDriverRmaOrderByCreatedAtDesc(String driverRma);

    /** Есть ли у водителя незавершённая заявка в заданном статусе (для правила «одна заявка в работе»). */
    boolean existsByDriverRmaAndStatus(String driverRma, String status);

    /** Блокирующая загрузка для одобрения/изменения (сериализует конкурентные действия по заявке). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from WaybillRequest r where r.id = :id")
    Optional<WaybillRequest> findByIdForUpdate(@Param("id") UUID id);
}
