package tj.mintrans.epd.masterdata.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tj.mintrans.epd.masterdata.domain.Driver;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DriverRepository extends JpaRepository<Driver, UUID> {
    Optional<Driver> findByRma(String rma);
    List<Driver> findByOrganizationId(UUID organizationId);

    /** Поиск водителей организации по ИНН(РМА) или ФИО (регистронезависимо), с лимитом.
     *  Пустой q → первые N (для просмотра списка без загрузки тысяч записей). */
    @Query("select d from Driver d where d.organizationId = :org "
            + "and (d.rma like concat('%', :q, '%') or upper(d.fullName) like upper(concat('%', :q, '%'))) "
            + "order by d.fullName")
    List<Driver> searchByOrg(@Param("org") UUID org, @Param("q") String q, Pageable pageable);
}
