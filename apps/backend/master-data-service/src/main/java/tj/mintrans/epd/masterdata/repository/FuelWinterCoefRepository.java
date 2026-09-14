package tj.mintrans.epd.masterdata.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tj.mintrans.epd.masterdata.domain.FuelWinterCoef;

import java.util.Optional;

public interface FuelWinterCoefRepository extends JpaRepository<FuelWinterCoef, Long> {

    Optional<FuelWinterCoef> findFirstByNameIgnoreCase(String name);
}
