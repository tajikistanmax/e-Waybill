package tj.mintrans.epd.masterdata.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tj.mintrans.epd.masterdata.domain.Direction;

import java.util.Optional;

public interface DirectionRepository extends JpaRepository<Direction, Long> {

    Optional<Direction> findFirstByTitleIgnoreCase(String title);
}
