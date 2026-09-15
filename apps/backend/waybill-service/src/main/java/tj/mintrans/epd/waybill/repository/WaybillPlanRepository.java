package tj.mintrans.epd.waybill.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tj.mintrans.epd.waybill.domain.WaybillPlan;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WaybillPlanRepository extends JpaRepository<WaybillPlan, UUID> {

    List<WaybillPlan> findByPlanYearInAndPlanKind(List<Integer> years, String planKind);

    List<WaybillPlan> findByPlanKindOrderByPlanYearDesc(String planKind);

    // Годовой план (plan_month IS NULL) — прежняя семантика «на весь год».
    Optional<WaybillPlan> findByOrganizationRmaAndPlanYearAndPlanKindAndPlanMonthIsNull(
            String organizationRma, int planYear, String planKind);

    Optional<WaybillPlan> findByOrganizationRmaIsNullAndPlanYearAndPlanKindAndPlanMonthIsNull(
            int planYear, String planKind);

    // Месячный план (plan_month = 1..12) — легаси Y-m, приоритетный при подборе плана отчёта.
    Optional<WaybillPlan> findByOrganizationRmaAndPlanYearAndPlanKindAndPlanMonth(
            String organizationRma, int planYear, String planKind, short planMonth);

    Optional<WaybillPlan> findByOrganizationRmaIsNullAndPlanYearAndPlanKindAndPlanMonth(
            int planYear, String planKind, short planMonth);
}
