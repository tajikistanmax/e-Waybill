package tj.mintrans.epd.masterdata.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tj.mintrans.epd.masterdata.domain.Cargo;

import java.util.Optional;
import java.util.UUID;

public interface CargoRepository extends JpaRepository<Cargo, UUID> {

    Optional<Cargo> findFirstByNameIgnoreCase(String name);
}
