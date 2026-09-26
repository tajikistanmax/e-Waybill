package tj.mintrans.epd.waybill.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tj.mintrans.epd.waybill.domain.FuelRecord;
import tj.mintrans.epd.waybill.domain.WorkDay;
import tj.mintrans.epd.waybill.service.FuelBalanceService;
import tj.mintrans.epd.waybill.service.WorkDayService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/** Многодневные путевые листы: рабочие дни и учёт топлива. */
@RestController
@RequestMapping("/api/v1/waybills/{id}")
public class WorkDayController {

    private final WorkDayService service;
    private final FuelBalanceService fuelBalance;

    public WorkDayController(WorkDayService service, FuelBalanceService fuelBalance) {
        this.service = service;
        this.fuelBalance = fuelBalance;
    }

    // ------------------------------------------------------------- запросы

    public record WorkDayRequest(
            @NotNull LocalDate workDate,
            LocalTime exitTime,
            LocalTime entryTime,
            Integer odometerExit,
            Integer odometerEntry,
            Integer laps,
            BigDecimal revenue,
            // Посуточные данные для многодневных пассажирских ПЛ (1-А, 3-С) — пока только
            // хранение, движок расчёта их не читает (см. spec/notes/04-гэп-анализ §2.2).
            BigDecimal conditionerHours,
            UUID clientId,
            LocalTime clientTime,
            // «Гашти ибтидоӣ» начала/конца смены: 'begin_path_a' | 'begin_path_b' | null.
            String beginPathA,
            String beginPathB,
            // Время работы спецоборудования за день, ЧЧ:ММ (2-Б / 5Б-БМ / спецтехника; сверка 25.09, B4).
            LocalTime specialWorkTime) {
    }

    public record FuelRequest(
            @NotNull @Min(1) @Max(5) Integer fuelType,
            UUID workDayId,
            BigDecimal fuelGiven,
            BigDecimal remainBeforeExit,
            BigDecimal remainEntry,
            BigDecimal additionalGiven,
            BigDecimal returned,
            /** Надбавка при t° ниже 0 °C, л (legacy coef_below_0). */
            BigDecimal coefBelow0,
            /** Норма к выдаче, л (legacy be_given, «Дода шавад»). */
            BigDecimal beGiven) {
    }

    /** Рабочий день вместе с записями топлива этого дня. */
    public record WorkDayView(WorkDay workDay, List<FuelRecord> fuel) {
    }

    /** Список дней + топливо, привязанное к ПЛ целиком (workDayId = null). */
    public record WorkDaysResponse(List<WorkDayView> workDays, List<FuelRecord> waybillFuel) {
    }

    // ------------------------------------------------------------- эндпоинты

    @PostMapping("/work-days")
    @PreAuthorize("hasAnyRole('DISPATCHER','SYSTEM_ADMIN')")
    public ResponseEntity<WorkDay> addWorkDay(@PathVariable UUID id, @Valid @RequestBody WorkDayRequest req) {
        var day = service.addWorkDay(id, req.workDate(), req.exitTime(), req.entryTime(),
                req.odometerExit(), req.odometerEntry(), req.laps(), req.revenue(),
                req.conditionerHours(), req.clientId(), req.clientTime(), req.beginPathA(), req.beginPathB());
        if (req.specialWorkTime() != null) {
            day = service.setSpecialWorkTime(id, day.getId(), req.specialWorkTime());
        }
        fuelBalance.recompute(id);
        return ResponseEntity.status(HttpStatus.CREATED).body(day);
    }

    /** Исправление рабочего дня (legacy: дни листа правились до закрытия). */
    @PutMapping("/work-days/{dayId}")
    @PreAuthorize("hasAnyRole('DISPATCHER','SYSTEM_ADMIN')")
    public WorkDay updateWorkDay(@PathVariable UUID id, @PathVariable UUID dayId, @Valid @RequestBody WorkDayRequest req) {
        var day = service.updateWorkDay(id, dayId, req.workDate(), req.exitTime(), req.entryTime(),
                req.odometerExit(), req.odometerEntry(), req.laps(), req.revenue(),
                req.conditionerHours(), req.clientId(), req.clientTime(), req.beginPathA(), req.beginPathB());
        if (req.specialWorkTime() != null || day.getSpecialWorkTime() != null) {
            day = service.setSpecialWorkTime(id, dayId, req.specialWorkTime());
        }
        fuelBalance.recompute(id);
        return day;
    }

    /** Удаление ошибочного рабочего дня вместе с его строками топлива. */
    @DeleteMapping("/work-days/{dayId}")
    @PreAuthorize("hasAnyRole('DISPATCHER','SYSTEM_ADMIN')")
    public ResponseEntity<Void> deleteWorkDay(@PathVariable UUID id, @PathVariable UUID dayId) {
        service.deleteWorkDay(id, dayId);
        fuelBalance.recompute(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/work-days")
    public WorkDaysResponse listWorkDays(@PathVariable UUID id) {
        var days = service.listWorkDays(id);
        var fuel = service.listFuel(id);
        var views = days.stream()
                .map(day -> new WorkDayView(day,
                        fuel.stream().filter(f -> day.getId().equals(f.getWorkDayId())).toList()))
                .toList();
        var waybillFuel = fuel.stream().filter(f -> f.getWorkDayId() == null).toList();
        return new WorkDaysResponse(views, waybillFuel);
    }

    @PostMapping("/fuel")
    @PreAuthorize("hasAnyRole('DISPATCHER','SYSTEM_ADMIN','FUEL_STATION')")
    public ResponseEntity<FuelRecord> addFuel(@PathVariable UUID id, @Valid @RequestBody FuelRequest req) {
        var record = service.addFuel(id, req.workDayId(), req.fuelType().shortValue(),
                req.fuelGiven(), req.remainBeforeExit(), req.remainEntry(),
                req.additionalGiven(), req.returned(), req.coefBelow0(), req.beGiven());
        fuelBalance.recompute(id);
        return ResponseEntity.status(HttpStatus.CREATED).body(fuelBalance.reload(record));
    }

    /** Исправление строки топлива (legacy: строки fuels правились до закрытия листа, в т.ч. АЗС). */
    @PutMapping("/fuel/{fuelId}")
    @PreAuthorize("hasAnyRole('DISPATCHER','SYSTEM_ADMIN','FUEL_STATION')")
    public FuelRecord updateFuel(@PathVariable UUID id, @PathVariable UUID fuelId, @Valid @RequestBody FuelRequest req) {
        var record = service.updateFuel(id, fuelId, req.workDayId(), req.fuelType().shortValue(),
                req.fuelGiven(), req.remainBeforeExit(), req.additionalGiven(), req.returned(), req.coefBelow0());
        fuelBalance.recompute(id);
        return fuelBalance.reload(record);
    }

    @DeleteMapping("/fuel/{fuelId}")
    @PreAuthorize("hasAnyRole('DISPATCHER','SYSTEM_ADMIN','FUEL_STATION')")
    public ResponseEntity<Void> deleteFuel(@PathVariable UUID id, @PathVariable UUID fuelId) {
        service.deleteFuel(id, fuelId);
        fuelBalance.recompute(id);
        return ResponseEntity.noContent().build();
    }
}
