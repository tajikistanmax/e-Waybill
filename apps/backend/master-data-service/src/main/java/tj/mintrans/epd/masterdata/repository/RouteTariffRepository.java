package tj.mintrans.epd.masterdata.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tj.mintrans.epd.masterdata.domain.RouteTariff;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RouteTariffRepository extends JpaRepository<RouteTariff, Long> {

    List<RouteTariff> findByRouteId(UUID routeId);

    Optional<RouteTariff> findFirstByRouteIdAndFuelId(UUID routeId, Short fuelId);

    Optional<RouteTariff> findFirstByRouteIdAndFuelIdIsNull(UUID routeId);
}
