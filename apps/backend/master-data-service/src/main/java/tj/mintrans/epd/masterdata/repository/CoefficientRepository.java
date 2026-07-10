package tj.mintrans.epd.masterdata.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tj.mintrans.epd.masterdata.domain.Coefficient;

import java.util.Optional;
import java.util.UUID;

public interface CoefficientRepository extends JpaRepository<Coefficient, UUID> {
    Optional<Coefficient> findByKindAndName(String kind, String name);
}
