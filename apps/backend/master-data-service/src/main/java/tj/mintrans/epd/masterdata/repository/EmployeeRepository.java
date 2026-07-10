package tj.mintrans.epd.masterdata.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tj.mintrans.epd.masterdata.domain.Employee;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EmployeeRepository extends JpaRepository<Employee, UUID> {
    Optional<Employee> findByRma(String rma);
    List<Employee> findByOrganizationId(UUID organizationId);
}
