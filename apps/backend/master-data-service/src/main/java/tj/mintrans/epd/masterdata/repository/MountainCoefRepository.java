package tj.mintrans.epd.masterdata.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tj.mintrans.epd.masterdata.domain.MountainCoef;

import java.util.Optional;

public interface MountainCoefRepository extends JpaRepository<MountainCoef, Long> {

    Optional<MountainCoef> findFirstByNameIgnoreCase(String name);
}
