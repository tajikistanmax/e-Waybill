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

public interface VehicleRepository extends JpaRepository<Vehicle, UUID> {
    Optional<Vehicle> findByRegistrationNumber(String registrationNumber);
    List<Vehicle> findByOrganizationId(UUID organizationId);
    List<Vehicle> findByOrganizationIdIn(Collection<UUID> organizationIds);

    /** ТС с указанным VIN в канонической форме — trim + верхний регистр, как у частичного
     *  уникального индекса uq_vehicle_vincode (V49). Список (а не Optional) — устойчив к возможным
     *  дублям в старых данных до применения индекса. Параметр {@code vin} уже канонизирован. */
    @Query("select v from Vehicle v where upper(trim(v.vincode)) = :vin")
    List<Vehicle> findByCanonicalVincode(@Param("vin") String vin);

    /** Поиск ТС организации по подстроке госномера (регистронезависимо), с лимитом (Pageable).
     *  Пустой q → первые N (для просмотра автопарка без загрузки тысяч записей). */
    @Query("select v from Vehicle v where v.organizationId = :org "
            + "and upper(v.registrationNumber) like upper(concat('%', :q, '%')) "
            + "order by v.registrationNumber")
    List<Vehicle> searchByOrg(@Param("org") UUID org, @Param("q") String q, Pageable pageable);

    /** То же для набора организаций (компания + её филиалы). */
    @Query("select v from Vehicle v where v.organizationId in :orgs "
            + "and upper(v.registrationNumber) like upper(concat('%', :q, '%')) "
            + "order by v.registrationNumber")
    List<Vehicle> searchByOrgs(@Param("orgs") Collection<UUID> orgs, @Param("q") String q, Pageable pageable);
}
