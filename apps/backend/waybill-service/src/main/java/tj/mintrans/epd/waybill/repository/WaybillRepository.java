package tj.mintrans.epd.waybill.repository;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import tj.mintrans.epd.waybill.domain.Waybill;
import tj.mintrans.epd.waybill.domain.WaybillStatus;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

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

    /** ПЛ организации в наборе статусов (кабинет пункта выдачи топлива — «на заправке»). */
    List<Waybill> findByOrganizationRmaAndStatusInOrderByCreatedAtDesc(String organizationRma,
                                                                      Collection<WaybillStatus> statuses);

    /** Просроченные документы для автоперехода в EXPIRED (LifecycleScheduler). */
    List<Waybill> findByStatusInAndValidToBefore(Collection<WaybillStatus> statuses, OffsetDateTime validTo);

    /** Завершённые документы старше срока ретенции — в ARCHIVED (LifecycleScheduler). */
    List<Waybill> findByStatusAndUpdatedAtBefore(WaybillStatus status, OffsetDateTime updatedAt);

    // --- Отчёты: выборка ПО ПЕРИОДУ и ПОТОКОМ вместо findAll (после Ф5 в таблице ~2.3 млн
    //     архивных ПЛ; findAll() в отчётах ронял сервис в OutOfMemoryError при -Xmx384m).
    //     Полуинтервал [from, to): границы дня считает WaybillPeriodScan. ---

    long countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(OffsetDateTime from, OffsetDateTime to);

    long countByOrganizationRmaInAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
            Collection<String> organizationRmas, OffsetDateTime from, OffsetDateTime to);

    /** Поток ПЛ периода (все организации), по возрастанию created_at; требует открытой транзакции. */
    @Query("select w from Waybill w where w.createdAt >= :from and w.createdAt < :to order by w.createdAt")
    @QueryHints(@QueryHint(name = org.hibernate.jpa.HibernateHints.HINT_FETCH_SIZE, value = "500"))
    Stream<Waybill> streamByPeriod(@Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);

    /** Поток ПЛ периода набора организаций (тенант: компания + филиалы). */
    @Query("select w from Waybill w where w.organizationRma in :rmas and w.createdAt >= :from and w.createdAt < :to "
            + "order by w.createdAt")
    @QueryHints(@QueryHint(name = org.hibernate.jpa.HibernateHints.HINT_FETCH_SIZE, value = "500"))
    Stream<Waybill> streamByPeriodAndOrganizations(@Param("rmas") Collection<String> organizationRmas,
                                                   @Param("from") OffsetDateTime from,
                                                   @Param("to") OffsetDateTime to);

    // Те же выборки, но только по одному статусу (отчёты по завершённым ПЛ: сводный перевозок, тренд) —
    // фильтр в SQL, чтобы не тянуть архив (ARCHIVED) через приложение.

    long countByStatusAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(WaybillStatus status,
                                                                       OffsetDateTime from, OffsetDateTime to);

    long countByOrganizationRmaInAndStatusAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
            Collection<String> organizationRmas, WaybillStatus status, OffsetDateTime from, OffsetDateTime to);

    @Query("select w from Waybill w where w.status = :status and w.createdAt >= :from and w.createdAt < :to "
            + "order by w.createdAt")
    @QueryHints(@QueryHint(name = org.hibernate.jpa.HibernateHints.HINT_FETCH_SIZE, value = "500"))
    Stream<Waybill> streamByPeriodAndStatus(@Param("status") WaybillStatus status,
                                            @Param("from") OffsetDateTime from,
                                            @Param("to") OffsetDateTime to);

    @Query("select w from Waybill w where w.organizationRma in :rmas and w.status = :status "
            + "and w.createdAt >= :from and w.createdAt < :to order by w.createdAt")
    @QueryHints(@QueryHint(name = org.hibernate.jpa.HibernateHints.HINT_FETCH_SIZE, value = "500"))
    Stream<Waybill> streamByPeriodAndOrganizationsAndStatus(@Param("rmas") Collection<String> organizationRmas,
                                                            @Param("status") WaybillStatus status,
                                                            @Param("from") OffsetDateTime from,
                                                            @Param("to") OffsetDateTime to);
}
