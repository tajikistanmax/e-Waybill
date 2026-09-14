package tj.mintrans.epd.masterdata.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tj.mintrans.epd.masterdata.domain.RouteType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RouteTypeRepository extends JpaRepository<RouteType, UUID> {
    /** Все типы маршрутов в порядке сортировки/кода — для листинга справочника и выпадающих списков. */
    List<RouteType> findAllByOrderBySortOrderAscCodeAsc();
    /** Поиск по естественному ключу (числовой код) — для upsert. */
    Optional<RouteType> findByCode(Short code);
}
