package tj.mintrans.epd.masterdata.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tj.mintrans.epd.masterdata.domain.Brand;

import java.util.List;
import java.util.Optional;

public interface BrandRepository extends JpaRepository<Brand, Long> {

    Optional<Brand> findFirstByNameIgnoreCase(String name);

    // Естественный ключ марки после V57 — (имя + модель); upsert должен различать модели.
    Optional<Brand> findFirstByNameIgnoreCaseAndModelIgnoreCase(String name, String model);

    List<Brand> findByNameIgnoreCase(String name);
}
