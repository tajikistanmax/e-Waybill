package tj.mintrans.epd.masterdata.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tj.mintrans.epd.masterdata.domain.Vehicle;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VehicleRepository extends JpaRepository<Vehicle, UUID>,
        org.springframework.data.jpa.repository.JpaSpecificationExecutor<Vehicle> {
    Optional<Vehicle> findByRegistrationNumber(String registrationNumber);

    /** Выгрузка для интеграторов legacy `ref/*` (updated_after, по 100; сверка 25.09, G1). */
    org.springframework.data.domain.Page<Vehicle> findByUpdatedAtAfter(java.time.OffsetDateTime updatedAfter, org.springframework.data.domain.Pageable pageable);

    /** Сколько ТС ссылаются на марку по названию — защита от удаления используемой марки. */
    long countByBrandIgnoreCase(String brand);

    /** Поиск ТС по части госномера (перевод парка между организациями). */
    List<Vehicle> findTop20ByRegistrationNumberContainingIgnoreCaseOrderByRegistrationNumberAsc(String part);
    List<Vehicle> findByOrganizationId(UUID organizationId);

    /** ТС организации постранично — legacy GET transports?organization_rma (G4). */
    org.springframework.data.domain.Page<Vehicle> findByOrganizationId(UUID organizationId,
                                                                       org.springframework.data.domain.Pageable pageable);
    List<Vehicle> findByOrganizationIdIn(Collection<UUID> organizationIds);

    /** ТС организации с данным номером стоянки — уникальность в пределах организации (legacy ParkingRequest, 12.13). */
    List<Vehicle> findByOrganizationIdAndParkingNumber(UUID organizationId, String parkingNumber);

    /** ТС с указанным VIN в канонической форме — trim + верхний регистр, как у частичного
     *  уникального индекса uq_vehicle_vincode (V49). Список (а не Optional) — устойчив к возможным
     *  дублям в старых данных до применения индекса. Параметр {@code vin} уже канонизирован. */
    @Query("select v from Vehicle v where upper(trim(v.vincode)) = :vin")
    List<Vehicle> findByCanonicalVincode(@Param("vin") String vin);

    /** Поиск ТС организации по подстроке госномера или номера стоянки (гаражного — как в legacy-мастере),
     *  регистронезависимо, с лимитом (Pageable). Пустой q → первые N (без загрузки тысяч записей). */
    @Query("select v from Vehicle v where v.organizationId = :org "
            + "and (upper(v.registrationNumber) like upper(concat('%', :q, '%')) or upper(v.parkingNumber) like upper(concat('%', :q, '%'))) "
            + "order by v.registrationNumber")
    List<Vehicle> searchByOrg(@Param("org") UUID org, @Param("q") String q, Pageable pageable);

    /** То же для набора организаций (компания + её филиалы). */
    @Query("select v from Vehicle v where v.organizationId in :orgs "
            + "and (upper(v.registrationNumber) like upper(concat('%', :q, '%')) or upper(v.parkingNumber) like upper(concat('%', :q, '%'))) "
            + "order by v.registrationNumber")
    List<Vehicle> searchByOrgs(@Param("orgs") Collection<UUID> orgs, @Param("q") String q, Pageable pageable);

    /**
     * Число ТС по организациям — [organizationId, count]. Для отчёта «Норматив выдачи ПЛ»
     * (waybill-service): одна агрегатная выборка вместо запроса списка ТС каждой из тысяч
     * организаций (N+1 по HTTP упирался в rate-limit master-data — 429).
     */
    @Query("select v.organizationId, count(v) from Vehicle v group by v.organizationId")
    List<Object[]> countByOrganization();

    /** То же, только ТС заданного вида (transport_type 1..6). */
    @Query("select v.organizationId, count(v) from Vehicle v where v.transportType = :type group by v.organizationId")
    List<Object[]> countByOrganizationForType(@Param("type") short type);
}
