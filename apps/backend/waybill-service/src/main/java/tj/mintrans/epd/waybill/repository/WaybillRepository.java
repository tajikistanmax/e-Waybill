package tj.mintrans.epd.waybill.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import tj.mintrans.epd.waybill.domain.Waybill;
import tj.mintrans.epd.waybill.domain.WaybillStatus;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WaybillRepository extends JpaRepository<Waybill, UUID> {

    Optional<Waybill> findByNumber(String number);

    /**
     * Загрузка с блокировкой строки (SELECT … FOR UPDATE) — сериализует конкурентные
     * мутации одного ПЛ. Без неё параллельные confirmMed/confirmTech теряли обновление:
     * JPA пишет все колонки, и последний коммит затирал чужой флаг medPassed/techPassed
     * (ПЛ застревал в CREATED с «потерянным» осмотром).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Waybill w where w.id = :id")
    Optional<Waybill> findByIdForUpdate(UUID id);

    List<Waybill> findByVehicleRegNumberAndStatusIn(String vehicleRegNumber, Collection<WaybillStatus> statuses);

    List<Waybill> findByDriverRmaAndStatusIn(String driverRma, Collection<WaybillStatus> statuses);

    List<Waybill> findByOrganizationRmaOrderByCreatedAtDesc(String organizationRma);

    List<Waybill> findByStatusOrderByCreatedAtDesc(WaybillStatus status);

    /** Активные ПЛ по набору статусов (GPS-мониторинг «на линии» для платформенных ролей). */
    List<Waybill> findByStatusInOrderByCreatedAtDesc(Collection<WaybillStatus> statuses);

    /** Просроченные документы для автоперехода в EXPIRED (LifecycleScheduler). */
    List<Waybill> findByStatusInAndValidToBefore(Collection<WaybillStatus> statuses, OffsetDateTime validTo);

    /** Завершённые документы старше срока ретенции — в ARCHIVED (LifecycleScheduler). */
    List<Waybill> findByStatusAndUpdatedAtBefore(WaybillStatus status, OffsetDateTime updatedAt);
}
