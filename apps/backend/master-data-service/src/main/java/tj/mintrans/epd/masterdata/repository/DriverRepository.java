package tj.mintrans.epd.masterdata.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tj.mintrans.epd.masterdata.domain.Driver;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DriverRepository extends JpaRepository<Driver, UUID>,
        org.springframework.data.jpa.repository.JpaSpecificationExecutor<Driver> {
    Optional<Driver> findByRma(String rma);

    /** Поиск водителя по части ФИО (перевод парка между организациями: искать можно по ИНН или ФИО). */
    List<Driver> findTop20ByFullNameContainingIgnoreCaseOrderByFullNameAsc(String part);
    List<Driver> findByOrganizationId(UUID organizationId);
    List<Driver> findByOrganizationIdIn(Collection<UUID> organizationIds);

    /** Поиск водителей организации по ИНН(РМА), ФИО (регистронезависимо) или табельному номеру, с лимитом.
     *  Пустой q → первые N (для просмотра списка без загрузки тысяч записей). */
    @Query("select d from Driver d where d.organizationId = :org "
            + "and (d.rma like concat('%', :q, '%') or upper(d.fullName) like upper(concat('%', :q, '%')) or d.tabNumber like concat('%', :q, '%')) "
            + "order by d.fullName")
    List<Driver> searchByOrg(@Param("org") UUID org, @Param("q") String q, Pageable pageable);

    /** То же для набора организаций (компания + её филиалы). */
    @Query("select d from Driver d where d.organizationId in :orgs "
            + "and (d.rma like concat('%', :q, '%') or upper(d.fullName) like upper(concat('%', :q, '%')) or d.tabNumber like concat('%', :q, '%')) "
            + "order by d.fullName")
    List<Driver> searchByOrgs(@Param("orgs") Collection<UUID> orgs, @Param("q") String q, Pageable pageable);

    /** Табельные номера водителей организации (для авто-присвоения max+1, legacy {@code DriverObserver}). */
    @Query("select d.tabNumber from Driver d where d.organizationId = :org and d.tabNumber is not null")
    List<String> findTabNumbersByOrganization(@Param("org") UUID org);
}
