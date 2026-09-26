package tj.mintrans.epd.waybill.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tj.mintrans.epd.waybill.domain.Malumotnoma;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MalumotnomaRepository extends JpaRepository<Malumotnoma, UUID>, JpaSpecificationExecutor<Malumotnoma> {

    List<Malumotnoma> findByOrganizationRmaOrderByCreatedAtDesc(String organizationRma);

    List<Malumotnoma> findByOrganizationRmaInOrderByCreatedAtDesc(Collection<String> organizationRmas);

    Optional<Malumotnoma> findByNumber(Long number);

    /** Следующий сквозной номер справки (продолжает нумерацию «Роҳхат», V31). */
    @Query(value = "select nextval('malumotnoma_number_seq')", nativeQuery = true)
    long nextNumber();

    /** Справки, в которых использован маршрут (удалять такой маршрут нельзя — только отключить). */
    @Query("select count(l) from MalumotnomaLine l where l.route.id = :routeId")
    long countLinesByRoute(@Param("routeId") UUID routeId);

    /** Отчёт по кассирам, все организации (платформенная роль). Аннулированные не входят. */
    @Query("""
            select m from Malumotnoma m
            where m.createdAt >= :from and m.createdAt < :to
              and m.annulledAt is null
              and (:issuer is null or m.issuerRma = :issuer)
            order by coalesce(m.issuerName, m.issuerRma, ''), m.createdAt, m.id
            """)
    List<Malumotnoma> forReport(@Param("from") OffsetDateTime from,
                                @Param("to") OffsetDateTime to,
                                @Param("issuer") String issuer);

    /** Отчёт по кассирам, набор организаций (тенант: своя компания + филиалы). Аннулированные не входят. */
    @Query("""
            select m from Malumotnoma m
            where m.createdAt >= :from and m.createdAt < :to
              and m.annulledAt is null
              and m.organizationRma in :orgs
              and (:issuer is null or m.issuerRma = :issuer)
            order by coalesce(m.issuerName, m.issuerRma, ''), m.createdAt, m.id
            """)
    List<Malumotnoma> forReportScoped(@Param("from") OffsetDateTime from,
                                      @Param("to") OffsetDateTime to,
                                      @Param("orgs") Collection<String> orgs,
                                      @Param("issuer") String issuer);
}
