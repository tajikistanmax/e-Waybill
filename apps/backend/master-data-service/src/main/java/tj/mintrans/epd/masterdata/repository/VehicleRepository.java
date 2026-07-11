package tj.mintrans.epd.masterdata.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tj.mintrans.epd.masterdata.domain.Vehicle;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VehicleRepository extends JpaRepository<Vehicle, UUID> {
    Optional<Vehicle> findByRegistrationNumber(String registrationNumber);
    List<Vehicle> findByOrganizationId(UUID organizationId);

    /** Поиск ТС организации по подстроке госномера (регистронезависимо), с лимитом (Pageable).
     *  Пустой q → первые N (для просмотра автопарка без загрузки тысяч записей). */
    @Query("select v from Vehicle v where v.organizationId = :org "
            + "and upper(v.registrationNumber) like upper(concat('%', :q, '%')) "
            + "order by v.registrationNumber")
    List<Vehicle> searchByOrg(@Param("org") UUID org, @Param("q") String q, Pageable pageable);
}
