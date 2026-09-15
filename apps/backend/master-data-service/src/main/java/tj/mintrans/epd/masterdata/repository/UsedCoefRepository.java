package tj.mintrans.epd.masterdata.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tj.mintrans.epd.masterdata.domain.UsedCoef;

import java.util.Optional;

public interface UsedCoefRepository extends JpaRepository<UsedCoef, Long> {

    Optional<UsedCoef> findFirstByYearAndKm(Short year, Integer km);
}
