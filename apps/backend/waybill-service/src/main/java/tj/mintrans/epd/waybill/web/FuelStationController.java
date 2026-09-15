package tj.mintrans.epd.waybill.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tj.mintrans.epd.waybill.config.CurrentUser;
import tj.mintrans.epd.waybill.domain.FuelRecord;
import tj.mintrans.epd.waybill.domain.Waybill;
import tj.mintrans.epd.waybill.domain.WaybillStatus;
import tj.mintrans.epd.waybill.repository.WaybillRepository;
import tj.mintrans.epd.waybill.service.WorkDayService;
import tj.mintrans.epd.waybill.web.error.ApiErrors.ForbiddenException;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Кабинет пункта выдачи топлива (перенос {@code Waybill1adFuelEmplCrudController}
 * из ИС «Роҳхат», роль {@code fuel_employee}).
 *
 * <p>Разделение обязанностей: диспетчер задаёт вид топлива и плановую выдачу ОДИН
 * раз при выписке листа; фактическую выдачу на колонке ведёт топливник. Роль
 * {@code FUEL_STATION} не правит сам путевой лист (маршрут, водитель, показания) —
 * только записи топлива своей организации.</p>
 */
@RestController
@RequestMapping("/api/v1/fuel-station")
@PreAuthorize("hasAnyRole('FUEL_STATION','SYSTEM_ADMIN')")
public class FuelStationController {

    /** Статусы, в которых лист «на заправке» — выдан водителю и ещё не закрыт. */
    private static final EnumSet<WaybillStatus> FUELABLE =
            EnumSet.of(WaybillStatus.READY, WaybillStatus.ISSUED, WaybillStatus.ACTIVE, WaybillStatus.RETURNED);

    private static final Map<Integer, String> FUEL_NAMES = Map.of(
            1, "Бензин", 2, "Дизель", 3, "Газ сжиженный", 4, "Газ природный", 5, "Электро");

    private final WaybillRepository waybills;
    private final WorkDayService workDays;
    private final CurrentUser currentUser;

    public FuelStationController(WaybillRepository waybills, WorkDayService workDays, CurrentUser currentUser) {
        this.waybills = waybills;
        this.workDays = workDays;
        this.currentUser = currentUser;
    }

    public record FuelStationWaybill(String id, String number, String type, String status,
                                     String vehicleRegNumber, String vehicleBrand, String driver,
                                     String route, double totalGiven, int fuelRecords) {
    }

    public record FuelLine(String id, int fuelType, String fuelName, BigDecimal fuelGiven,
                           BigDecimal remainBeforeExit, BigDecimal remainEntry,
                           BigDecimal additionalGiven, BigDecimal returned, String at) {
    }

    public record RecordFuelRequest(
            @NotNull @Min(1) @Max(5) Integer fuelType,
            @NotNull BigDecimal fuelGiven,
            BigDecimal remainBeforeExit,
            BigDecimal remainEntry,
            BigDecimal additionalGiven,
            BigDecimal returned) {
    }

    @GetMapping("/waybills")
    public List<FuelStationWaybill> list() {
        String org = org();
        return waybills.findByOrganizationRmaOrderByCreatedAtDesc(org).stream()
                .filter(w -> FUELABLE.contains(w.getStatus()))
                .map(w -> {
                    List<FuelRecord> fuel = workDays.listFuel(w.getId());
                    double total = fuel.stream()
                            .map(FuelRecord::getFuelGiven)
                            .filter(java.util.Objects::nonNull)
                            .mapToDouble(BigDecimal::doubleValue).sum();
                    Map<String, Object> veh = w.getVehicleSnapshot() == null ? Map.of() : w.getVehicleSnapshot();
                    Map<String, Object> drv = w.getDriverSnapshot() == null ? Map.of() : w.getDriverSnapshot();
                    return new FuelStationWaybill(
                            w.getId().toString(), w.getNumber(), w.getWaybillType().legacyForm(),
                            w.getStatus().name(), w.getVehicleRegNumber(), str(veh.get("brand")),
                            drv.get("fullName") != null ? str(drv.get("fullName")) : w.getDriverRma(),
                            w.getRoute(), Math.round(total * 100.0) / 100.0, fuel.size());
                })
                .toList();
    }

    @GetMapping("/waybills/{id}/fuel")
    public List<FuelLine> fuel(@PathVariable UUID id) {
        assertOwn(id);
        return workDays.listFuel(id).stream()
                .map(f -> new FuelLine(f.getId().toString(), f.getFuelType(),
                        FUEL_NAMES.getOrDefault((int) f.getFuelType(), "Топливо " + f.getFuelType()),
                        f.getFuelGiven(), f.getRemainBeforeExit(), f.getRemainEntry(),
                        f.getAdditionalGiven(), f.getReturned(),
                        f.getCreatedAt() == null ? null : f.getCreatedAt().toString()))
                .toList();
    }

    @PostMapping("/waybills/{id}/fuel")
    public ResponseEntity<FuelRecord> record(@PathVariable UUID id, @Valid @RequestBody RecordFuelRequest req) {
        assertOwn(id);
        FuelRecord saved = workDays.addFuel(id, null, req.fuelType().shortValue(),
                req.fuelGiven(), req.remainBeforeExit(), req.remainEntry(),
                req.additionalGiven(), req.returned());
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    // ------------------------------------------------------------------

    private String org() {
        return currentUser.organizationRma()
                .orElseThrow(() -> new ForbiddenException("В токене нет организации пункта выдачи топлива"));
    }

    private void assertOwn(UUID id) {
        if (currentUser.hasRole("SYSTEM_ADMIN")) {
            return;
        }
        Waybill wb = waybills.findById(id)
                .orElseThrow(() -> new tj.mintrans.epd.waybill.web.error.ApiErrors.NotFoundException("Путевой лист не найден"));
        if (!org().equals(wb.getOrganizationRma())) {
            throw new tj.mintrans.epd.waybill.web.error.ApiErrors.NotFoundException("Путевой лист не найден");
        }
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }
}
