package tj.mintrans.epd.masterdata.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tj.mintrans.epd.masterdata.domain.FuelNorm;

import java.util.Optional;
import java.util.UUID;

public interface FuelNormRepository extends JpaRepository<FuelNorm, UUID> {
    Optional<FuelNorm> findByTransportTypeAndBrand(short transportType, String brand);
    Optional<FuelNorm> findByTransportTypeAndBrandIsNull(short transportType);
}
