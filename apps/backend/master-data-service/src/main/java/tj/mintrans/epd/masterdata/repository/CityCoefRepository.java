package tj.mintrans.epd.masterdata.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tj.mintrans.epd.masterdata.domain.CityCoef;

import java.util.Optional;

public interface CityCoefRepository extends JpaRepository<CityCoef, Long> {

    Optional<CityCoef> findFirstByNameIgnoreCase(String name);
}
