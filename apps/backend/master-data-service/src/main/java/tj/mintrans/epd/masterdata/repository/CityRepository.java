package tj.mintrans.epd.masterdata.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tj.mintrans.epd.masterdata.domain.City;

import java.util.List;
import java.util.UUID;

public interface CityRepository extends JpaRepository<City, UUID> {
    List<City> findAllByOrderByRegionIdAscNameAsc();
    List<City> findByRegionIdOrderByNameAsc(short regionId);
}
