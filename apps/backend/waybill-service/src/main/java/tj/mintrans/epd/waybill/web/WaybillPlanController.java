package tj.mintrans.epd.waybill.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tj.mintrans.epd.waybill.domain.WaybillPlan;
import tj.mintrans.epd.waybill.repository.WaybillPlanRepository;
import tj.mintrans.epd.waybill.service.RegionalReportService;
import tj.mintrans.epd.waybill.web.error.ApiErrors.NotFoundException;

import java.util.List;
import java.util.UUID;

/**
 * Плановые показатели перевозок (основа сравнения «план / факт» в сводном отчёте).
 * Правит только Минтранс.
 */
@RestController
@RequestMapping("/api/v1/waybill-plans")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','MINTRANS_ANALYST')") // класс — только для чтения (list); запись переопределена ниже
public class WaybillPlanController {

    private final WaybillPlanRepository plans;
    private final RegionalReportService regional;

    public WaybillPlanController(WaybillPlanRepository plans, RegionalReportService regional) {
        this.plans = plans;
        this.regional = regional;
    }

    public record PlanRequest(
            String organizationRma,
            Short regionId,
            @NotNull @Min(2000) Integer planYear,
            // Месяц плана (1–12), опционально: null — план на весь год (как раньше);
            // указан — план на конкретный месяц (легаси Y-m), приоритетный в отчёте за этот месяц.
            @Min(1) @Max(12) Short planMonth,
            @NotBlank String planKind,
            @NotNull Double volumeThousand,
            @NotNull Double rotationMillion,
            String note) {
    }

    @GetMapping
    public List<WaybillPlan> list(@RequestParam(required = false) String kind) {
        return regional.listPlans(kind);
    }

    // Запись — только SYSTEM_ADMIN (найдено приёмочным тестированием 2026-09-04:
    // класс-level @PreAuthorize давал MINTRANS_ANALYST полный CRUD, хотя JavaDoc
    // и бизнес-требование — «аналитик строго read-only»; сами показатели формируют
    // официальный план/факт-отчёт, менять их аналитику не положено).
    @PostMapping
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<WaybillPlan> upsert(@Valid @RequestBody PlanRequest req) {
        String rma = req.organizationRma() == null || req.organizationRma().isBlank() ? null : req.organizationRma().trim();
        Short month = req.planMonth();
        WaybillPlan plan = (rma == null
                ? (month == null
                    ? plans.findByOrganizationRmaIsNullAndPlanYearAndPlanKindAndPlanMonthIsNull(req.planYear(), req.planKind())
                    : plans.findByOrganizationRmaIsNullAndPlanYearAndPlanKindAndPlanMonth(req.planYear(), req.planKind(), month))
                : (month == null
                    ? plans.findByOrganizationRmaAndPlanYearAndPlanKindAndPlanMonthIsNull(rma, req.planYear(), req.planKind())
                    : plans.findByOrganizationRmaAndPlanYearAndPlanKindAndPlanMonth(rma, req.planYear(), req.planKind(), month)))
                .orElseGet(WaybillPlan::new);
        boolean created = plan.getId() == null;
        plan.setOrganizationRma(rma);
        plan.setRegionId(req.regionId());
        plan.setPlanYear(req.planYear());
        plan.setPlanMonth(month);
        plan.setPlanKind(req.planKind());
        plan.setVolumeThousand(req.volumeThousand());
        plan.setRotationMillion(req.rotationMillion());
        plan.setNote(req.note());
        WaybillPlan saved = plans.save(plan);
        return ResponseEntity.status(created ? HttpStatus.CREATED : HttpStatus.OK).body(saved);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        if (!plans.existsById(id)) {
            throw new NotFoundException("План не найден");
        }
        plans.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
