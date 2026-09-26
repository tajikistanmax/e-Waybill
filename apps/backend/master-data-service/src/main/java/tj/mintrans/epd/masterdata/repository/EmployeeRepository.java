package tj.mintrans.epd.masterdata.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tj.mintrans.epd.masterdata.domain.Employee;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EmployeeRepository extends JpaRepository<Employee, UUID>,
        org.springframework.data.jpa.repository.JpaSpecificationExecutor<Employee> {
    Optional<Employee> findByRma(String rma);

    /** Выгрузка для интеграторов legacy `ref/*` (updated_after, по 100; сверка 25.09, G1). */
    org.springframework.data.domain.Page<Employee> findByUpdatedAtAfter(java.time.OffsetDateTime updatedAfter, org.springframework.data.domain.Pageable pageable);

    /** Поиск сотрудника по части ФИО (перевод между организациями: по ИНН или ФИО). */
    List<Employee> findTop20ByNameContainingIgnoreCaseOrderByNameAsc(String part);
    List<Employee> findByOrganizationId(UUID organizationId);

    /** Сотрудники организации постранично — legacy GET employees?organization_rma (G4). */
    org.springframework.data.domain.Page<Employee> findByOrganizationId(UUID organizationId,
                                                                        org.springframework.data.domain.Pageable pageable);
    List<Employee> findByOrganizationIdIn(Collection<UUID> organizationIds);
}
