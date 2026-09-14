package tj.mintrans.epd.masterdata.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tj.mintrans.epd.masterdata.domain.Region;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RegionRepository extends JpaRepository<Region, UUID> {
    /** Все регионы в порядке сортировки/кода — для листинга справочника. */
    List<Region> findAllByOrderBySortOrderAscCodeAsc();
    /** Поиск по естественному ключу (числовой код 1..7) — для upsert. */
    Optional<Region> findByCode(Short code);
}
