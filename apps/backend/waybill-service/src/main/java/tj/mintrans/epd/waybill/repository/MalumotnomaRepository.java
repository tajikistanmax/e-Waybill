package tj.mintrans.epd.waybill.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tj.mintrans.epd.waybill.domain.Malumotnoma;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface MalumotnomaRepository extends JpaRepository<Malumotnoma, UUID> {

    List<Malumotnoma> findByOrganizationRmaOrderByCreatedAtDesc(String organizationRma);

    List<Malumotnoma> findByOrganizationRmaInOrderByCreatedAtDesc(Collection<String> organizationRmas);

    /** Отчёт по кассирам, все организации (платформенная роль). */
    @Query("""
            select m from Malumotnoma m
            where m.createdAt >= :from and m.createdAt < :to
              and (:issuer is null or m.issuerRma = :issuer)
            order by coalesce(m.issuerName, m.issuerRma, ''), m.createdAt, m.id
            """)
    List<Malumotnoma> forReport(@Param("from") OffsetDateTime from,
                                @Param("to") OffsetDateTime to,
                                @Param("issuer") String issuer);

    /** Отчёт по кассирам, набор организаций (тенант: своя компания + филиалы). */
    @Query("""
            select m from Malumotnoma m
            where m.createdAt >= :from and m.createdAt < :to
              and m.organizationRma in :orgs
              and (:issuer is null or m.issuerRma = :issuer)
            order by coalesce(m.issuerName, m.issuerRma, ''), m.createdAt, m.id
            """)
    List<Malumotnoma> forReportScoped(@Param("from") OffsetDateTime from,
                                      @Param("to") OffsetDateTime to,
                                      @Param("orgs") Collection<String> orgs,
                                      @Param("issuer") String issuer);
}
