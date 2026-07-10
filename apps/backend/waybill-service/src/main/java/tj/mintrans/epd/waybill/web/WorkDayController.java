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
import tj.mintrans.epd.waybill.domain.FuelRecord;
import tj.mintrans.epd.waybill.domain.WorkDay;
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

    public WorkDayController(WorkDayService service) {
        this.service = service;
    }

    // ------------------------------------------------------------- запросы

    public record WorkDayRequest(
            @NotNull LocalDate workDate,
            LocalTime exitTime,
            LocalTime entryTime,
            Integer odometerExit,
            Integer odometerEntry,
            Integer laps,
            BigDecimal revenue) {
    }

    public record FuelRequest(
            @NotNull @Min(1) @Max(5) Integer fuelType,
            UUID workDayId,
            BigDecimal fuelGiven,
            BigDecimal remainBeforeExit,
            BigDecimal remainEntry) {
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
                req.odometerExit(), req.odometerEntry(), req.laps(), req.revenue());
        return ResponseEntity.status(HttpStatus.CREATED).body(day);
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
    @PreAuthorize("hasAnyRole('DISPATCHER','SYSTEM_ADMIN')")
    public ResponseEntity<FuelRecord> addFuel(@PathVariable UUID id, @Valid @RequestBody FuelRequest req) {
        var record = service.addFuel(id, req.workDayId(), req.fuelType().shortValue(),
                req.fuelGiven(), req.remainBeforeExit(), req.remainEntry());
        return ResponseEntity.status(HttpStatus.CREATED).body(record);
    }
}
