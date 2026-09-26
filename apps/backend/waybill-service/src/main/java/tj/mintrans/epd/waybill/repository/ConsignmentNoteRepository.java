package tj.mintrans.epd.waybill.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tj.mintrans.epd.waybill.domain.ConsignmentNote;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ConsignmentNoteRepository extends JpaRepository<ConsignmentNote, UUID> {

    List<ConsignmentNote> findByWaybillIdOrderByNoteDateAscNumberAsc(UUID waybillId);

    long countByWaybillId(UUID waybillId);

    /** Борхаты пачки листов одним запросом — для сводок (без N+1 по листам). */
    List<ConsignmentNote> findByWaybillIdIn(Collection<UUID> waybillIds);

    /** Число борхатов по листам за период дат борхата [from, to] — графа 21 сводного счётного отчёта. */
    @Query("select n.waybillId, count(n) from ConsignmentNote n where n.noteDate >= :from and n.noteDate <= :to "
            + "group by n.waybillId")
    List<Object[]> countByWaybillInPeriod(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
