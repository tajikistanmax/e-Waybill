package tj.mintrans.epd.masterdata.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tj.mintrans.epd.masterdata.domain.Driver;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DriverRepository extends JpaRepository<Driver, UUID> {
    Optional<Driver> findByRma(String rma);
    List<Driver> findByOrganizationId(UUID organizationId);
}
