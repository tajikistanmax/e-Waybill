package tj.mintrans.epd.waybill.web;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tj.mintrans.epd.waybill.service.ReportService;

import java.time.LocalDate;
import java.util.List;

/**
 * Отчёты: сводка, журнал диспетчера, разрезы по водителям/ТС/топливу.
 * Мультиарендность: не-админ видит только свою организацию
 * (organizationRma-параметр учитывается только для платформенных ролей).
 */
@RestController
@RequestMapping("/api/v1/reports")
@PreAuthorize("hasAnyRole('DISPATCHER','COMPANY_ADMIN','SYSTEM_ADMIN','MINTRANS_ANALYST')")
public class ReportController {

    private final ReportService reports;

    public ReportController(ReportService reports) {
        this.reports = reports;
    }

    @GetMapping("/summary")
    public ReportService.SummaryReport summary(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String organizationRma) {
        return reports.summary(from, to, organizationRma);
    }

    @GetMapping("/dispatcher-journal")
    public List<ReportService.JournalRow> dispatcherJournal(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String organizationRma) {
        return reports.dispatcherJournal(date, organizationRma);
    }

    @GetMapping("/by-driver")
    public List<ReportService.DriverRow> byDriver(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String organizationRma) {
        return reports.byDriver(from, to, organizationRma);
    }

    @GetMapping("/by-vehicle")
    public List<ReportService.VehicleRow> byVehicle(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String organizationRma) {
        return reports.byVehicle(from, to, organizationRma);
    }

    @GetMapping("/fuel")
    public List<ReportService.FuelRow> fuel(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String organizationRma) {
        return reports.fuel(from, to, organizationRma);
    }
}
