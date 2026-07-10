package tj.mintrans.epd.masterdata.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tj.mintrans.epd.masterdata.domain.Vehicle;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VehicleRepository extends JpaRepository<Vehicle, UUID> {
    Optional<Vehicle> findByRegistrationNumber(String registrationNumber);
    List<Vehicle> findByOrganizationId(UUID organizationId);
}
