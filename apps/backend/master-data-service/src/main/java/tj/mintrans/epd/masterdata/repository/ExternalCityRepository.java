package tj.mintrans.epd.masterdata.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tj.mintrans.epd.masterdata.domain.ExternalCity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExternalCityRepository extends JpaRepository<ExternalCity, UUID> {
    List<ExternalCity> findAllByOrderByCountryCodeAscSortOrderAscNameRuAsc();
    List<ExternalCity> findByCountryCodeOrderBySortOrderAscNameRuAsc(String countryCode);
    Optional<ExternalCity> findByCountryCodeAndNameRuIgnoreCase(String countryCode, String nameRu);

    /** Поиск по названию во всех странах (сверка 25.09, E6: 18 тыс. городов, в «Роҳхат» — поиск в списке). */
    List<ExternalCity> findTop200ByNameRuContainingIgnoreCaseOrNameTjContainingIgnoreCaseOrderByNameRuAsc(
            String nameRu, String nameTj);
}
