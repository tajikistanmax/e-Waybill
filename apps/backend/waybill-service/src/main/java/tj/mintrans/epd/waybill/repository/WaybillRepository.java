package tj.mintrans.epd.waybill.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
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

    /** Все ПЛ водителя (основной или второй), новые сверху — для мобильного приложения. */
    @Query("select w from Waybill w where w.driverRma = :rma or w.secondDriverRma = :rma order by w.createdAt desc")
    List<Waybill> findForDriver(String rma);

    List<Waybill> findByOrganizationRmaOrderByCreatedAtDesc(String organizationRma);

    /** ПЛ набора организаций (компания + её филиалы), новые сверху. */
    List<Waybill> findByOrganizationRmaInOrderByCreatedAtDesc(Collection<String> organizationRmas);

    List<Waybill> findByStatusOrderByCreatedAtDesc(WaybillStatus status);

    // --- Листинг реестра с защитой от боевого объёма (после миграции Ф5 в waybill ~2.3 млн
    //     архивных ПЛ, source='MIGRATED'). Дефолтный реестр ИСКЛЮЧАЕТ архив и ОГРАНИЧЕН
    //     Pageable; архив доступен по опту (org/номер), тоже с лимитом. Полноценная серверная
    //     пагинация — отдельная задача. ---
    List<Waybill> findBySourceNotOrderByCreatedAtDesc(String source, Pageable pageable);

    List<Waybill> findByStatusAndSourceNotOrderByCreatedAtDesc(WaybillStatus status, String source, Pageable pageable);

    List<Waybill> findByOrganizationRmaAndSourceNotOrderByCreatedAtDesc(String organizationRma, String source, Pageable pageable);

    List<Waybill> findByOrganizationRmaInAndSourceNotOrderByCreatedAtDesc(Collection<String> organizationRmas, String source, Pageable pageable);

    List<Waybill> findByOrganizationRmaInAndStatusAndSourceNotOrderByCreatedAtDesc(Collection<String> organizationRmas, WaybillStatus status, String source, Pageable pageable);

    // Явный доступ к архиву (archived=true) — организационно-ограниченный, тоже с лимитом:
    List<Waybill> findByOrganizationRmaInOrderByCreatedAtDesc(Collection<String> organizationRmas, Pageable pageable);

    List<Waybill> findByOrganizationRmaOrderByCreatedAtDesc(String organizationRma, Pageable pageable);

    List<Waybill> findByStatusOrderByCreatedAtDesc(WaybillStatus status, Pageable pageable);

    /** Активные ПЛ по набору статусов (GPS-мониторинг «на линии» для платформенных ролей). */
    List<Waybill> findByStatusInOrderByCreatedAtDesc(Collection<WaybillStatus> statuses);

    /** Просроченные документы для автоперехода в EXPIRED (LifecycleScheduler). */
    List<Waybill> findByStatusInAndValidToBefore(Collection<WaybillStatus> statuses, OffsetDateTime validTo);

    /** Завершённые документы старше срока ретенции — в ARCHIVED (LifecycleScheduler). */
    List<Waybill> findByStatusAndUpdatedAtBefore(WaybillStatus status, OffsetDateTime updatedAt);
}
