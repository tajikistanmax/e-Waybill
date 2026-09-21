package tj.mintrans.epd.waybill.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tj.mintrans.epd.waybill.domain.WorkDay;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface WorkDayRepository extends JpaRepository<WorkDay, UUID> {

    List<WorkDay> findByWaybillIdOrderByWorkDate(UUID waybillId);

    boolean existsByWaybillIdAndWorkDate(UUID waybillId, LocalDate workDate);

    long countByWaybillId(UUID waybillId);

    // --- Агрегаты для сводок (ReportService): считает БД, а не findAll() в память (Ф5: 2,3 млн ПЛ). ---

    /** Σ выручки рабочих дней по ПЛ периода [from, to) — все организации; null, если строк нет. */
    @Query("select sum(d.revenue) from WorkDay d, Waybill w where d.waybillId = w.id "
            + "and w.createdAt >= :from and w.createdAt < :to")
    BigDecimal sumRevenueInPeriod(@Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);

    @Query("select sum(d.revenue) from WorkDay d, Waybill w where d.waybillId = w.id "
            + "and w.organizationRma in :rmas and w.createdAt >= :from and w.createdAt < :to")
    BigDecimal sumRevenueInPeriodForOrganizations(@Param("rmas") Collection<String> organizationRmas,
                                                  @Param("from") OffsetDateTime from,
                                                  @Param("to") OffsetDateTime to);
}
