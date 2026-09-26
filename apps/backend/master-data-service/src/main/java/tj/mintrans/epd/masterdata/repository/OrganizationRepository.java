package tj.mintrans.epd.masterdata.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tj.mintrans.epd.masterdata.domain.Organization;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrganizationRepository extends JpaRepository<Organization, UUID> {
    Optional<Organization> findByRma(String rma);

    /** Выгрузка для интеграторов legacy `ref/*` (updated_after, по 100; сверка 25.09, G1). */
    org.springframework.data.domain.Page<Organization> findByUpdatedAtAfter(java.time.OffsetDateTime updatedAfter, org.springframework.data.domain.Pageable pageable);

    /** Филиалы головной компании. */
    List<Organization> findByParentRma(String parentRma);

    List<Organization> findByRmaIn(Collection<String> rmas);

    /**
     * Идентификаторы организаций региона и/или города — географический отбор постраничных
     * реестров. Возвращаются только id: полные карточки 604 организаций для фильтра не нужны.
     * Незаданный параметр (null) ограничения не накладывает.
     *
     * <p>Ветки разделены намеренно. Вариант с {@code (:city is null or lower(o.cityName) = lower(:city))}
     * падал на PostgreSQL с «function lower(bytea) does not exist»: null-строка уходит в драйвер
     * без типа и становится {@code bytea} (находка приёмки 22.09.2026). Условие «или null» в SQL
     * для строковых параметров не использовать.
     */
    default List<UUID> findIdsByRegionAndCity(Short region, String city) {
        if (region != null && city != null) {
            return findIdsByRegionAndCityName(region, city);
        }
        if (region != null) {
            return findIdsByRegion(region);
        }
        if (city != null) {
            return findIdsByCityName(city);
        }
        return findAllIds();
    }

    @Query("select o.id from Organization o where o.regionId = :region and lower(o.cityName) = lower(:city)")
    List<UUID> findIdsByRegionAndCityName(@Param("region") Short region, @Param("city") String city);

    @Query("select o.id from Organization o where o.regionId = :region")
    List<UUID> findIdsByRegion(@Param("region") Short region);

    long countByRegionId(Short regionId);

    @Query("select o.id from Organization o where lower(o.cityName) = lower(:city)")
    List<UUID> findIdsByCityName(@Param("city") String city);

    @Query("select o.id from Organization o")
    List<UUID> findAllIds();

    /** Идентификаторы организаций, чьё название содержит подстроку (уже в нижнем регистре). */
    @Query("select o.id from Organization o where lower(coalesce(o.name, '')) like concat('%', :q, '%')")
    List<UUID> findIdsByNameLike(@Param("q") String q);
}
