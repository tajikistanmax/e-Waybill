package tj.mintrans.epd.masterdata.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tj.mintrans.epd.masterdata.domain.Tariff;

import java.util.Optional;
import java.util.UUID;

public interface TariffRepository extends JpaRepository<Tariff, UUID> {
    Optional<Tariff> findByTransportTypeAndFuelType(short transportType, Short fuelType);
    Optional<Tariff> findByTransportTypeAndFuelTypeIsNull(short transportType);
}
