package tj.mintrans.epd.waybill.web;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tj.mintrans.epd.waybill.calc.report.PassengerVolumeTrend;
import tj.mintrans.epd.waybill.calc.report.RegionalCountReport;
import tj.mintrans.epd.waybill.calc.report.RegionalReport;
import tj.mintrans.epd.waybill.calc.report.ReportType;
import tj.mintrans.epd.waybill.calc.report.WaybillReport;
import tj.mintrans.epd.waybill.print.ReportXlsxWriter;
import tj.mintrans.epd.waybill.service.InspectionJournalService;
import tj.mintrans.epd.waybill.service.RegionalReportService;
import tj.mintrans.epd.waybill.service.ReportService;
import tj.mintrans.epd.waybill.service.WaybillReportService;

import java.nio.charset.StandardCharsets;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Отчёты: сводка, журнал диспетчера, разрезы по водителям/ТС/топливу.
 * Мультиарендность: не-админ видит только свою организацию
 * (organizationRma-параметр учитывается только для платформенных ролей).
 *
 * <p>Доступ: операционные и управляющие роли перевозчика + надзор. ACCOUNTANT —
 * обязательно: отчётность его основная функция, стартовая страница бухгалтера —
 * именно сводный отчёт. BRANCH_ADMIN — то же в пределах своего филиала.
 * Сводные отчёты Минтранса ({@code /regional*}, {@code /waybill-norm*}) закрыты
 * отдельными @PreAuthorize на методах — они перекрывают это правило класса.</p>
 */
@RestController
@RequestMapping("/api/v1/reports")
@PreAuthorize("hasAnyRole('DISPATCHER','ACCOUNTANT','COMPANY_ADMIN','BRANCH_ADMIN','SYSTEM_ADMIN','MINTRANS_ANALYST')")
public class ReportController {

    private final ReportService reports;
    private final WaybillReportService typedReports;
    private final RegionalReportService regionalReports;
    private final InspectionJournalService journals;
    private final ReportXlsxWriter xlsx;

    public ReportController(ReportService reports, WaybillReportService typedReports,
                            RegionalReportService regionalReports, InspectionJournalService journals,
                            ReportXlsxWriter xlsx) {
        this.reports = reports;
        this.typedReports = typedReports;
        this.regionalReports = regionalReports;
        this.journals = journals;
        this.xlsx = xlsx;
    }

    /**
     * Сводка за период. Инспектору дорожного контроля она доступна (в отличие от
     * экономических разрезов перевозчика — расход топлива, зарплата водителей):
     * это надзорная картина по документам, а не внутренняя экономика компании.
     */
    @GetMapping("/summary")
    @PreAuthorize("hasAnyRole('DISPATCHER','ACCOUNTANT','COMPANY_ADMIN','BRANCH_ADMIN','SYSTEM_ADMIN','MINTRANS_ANALYST','INSPECTOR')")
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

    // ---------------------------- типовые отчёты движка «Роҳхат» (перенос)

    /**
     * Пассажирский отчёт (формы 1-А, 1-АД, 1-АДЕ, 3-С), тип разреза — {@code ?type=}.
     * Необязательный отбор по ТС / водителю ({@code vehicleRegNumber}, {@code driverRma}) —
     * legacy {@code report_details} (детализация по одному ТС), MIGRATION.md 6.7.
     */
    @GetMapping("/passenger")
    public WaybillReport passenger(
            @RequestParam ReportType type,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String organizationRma,
            @RequestParam(required = false) String vehicleRegNumber,
            @RequestParam(required = false) String driverRma) {
        return typedReports.passenger(type, from, to, organizationRma,
                WaybillReportService.Filter.of(vehicleRegNumber, driverRma));
    }

    /** Грузовой отчёт (формы 2-Б, 5Б-БМ), тип разреза — {@code ?type=}; отбор по ТС / водителю — как у пассажирского. */
    @GetMapping("/cargo")
    public WaybillReport cargo(
            @RequestParam ReportType type,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String organizationRma,
            @RequestParam(required = false) String vehicleRegNumber,
            @RequestParam(required = false) String driverRma) {
        return typedReports.cargo(type, from, to, organizationRma,
                WaybillReportService.Filter.of(vehicleRegNumber, driverRma));
    }

    /** Пассажирский отчёт в XLSX. */
    @GetMapping("/passenger.xlsx")
    public ResponseEntity<byte[]> passengerXlsx(
            @RequestParam ReportType type,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String organizationRma,
            @RequestParam(required = false) String vehicleRegNumber,
            @RequestParam(required = false) String driverRma) {
        return xlsxResponse(typedReports.passenger(type, from, to, organizationRma,
                WaybillReportService.Filter.of(vehicleRegNumber, driverRma)));
    }

    /** Грузовой отчёт в XLSX. */
    @GetMapping("/cargo.xlsx")
    public ResponseEntity<byte[]> cargoXlsx(
            @RequestParam ReportType type,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String organizationRma,
            @RequestParam(required = false) String vehicleRegNumber,
            @RequestParam(required = false) String driverRma) {
        return xlsxResponse(typedReports.cargo(type, from, to, organizationRma,
                WaybillReportService.Filter.of(vehicleRegNumber, driverRma)));
    }

    /**
     * Сводный региональный отчёт Минтранса (регион → город → предприятие, план / факт).
     *
     * @param typeCompany фильтр «ведомственный / общий» ({@code Organization.typeCompany}:
     *        1 — общего пользования, 2 — ведомственная); не передан — все организации.
     */
    @GetMapping("/regional")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','MINTRANS_ANALYST')")
    public RegionalReport regional(
            @RequestParam(defaultValue = "PASSENGER") String bill,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Short typeCompany) {
        return "CARGO".equalsIgnoreCase(bill)
                ? regionalReports.cargoTransportation(from, to, typeCompany)
                : regionalReports.transportation(from, to, typeCompany);
    }

    /** Сводный отчёт «Количество путевых листов» (§6.3, 19 счётчиков + 2 доп. счётчика накладных). */
    @GetMapping("/regional-count")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','MINTRANS_ANALYST')")
    public RegionalCountReport regionalCount(
            @RequestParam(defaultValue = "ALL") String bill,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Short typeCompany) {
        return regionalReports.countWaybills(bill, from, to, typeCompany);
    }

    @GetMapping("/regional-count.xlsx")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','MINTRANS_ANALYST')")
    public ResponseEntity<byte[]> regionalCountXlsx(
            @RequestParam(defaultValue = "ALL") String bill,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Short typeCompany) {
        return fileXlsx(xlsx.writeRegionalCount(regionalReports.countWaybills(bill, from, to, typeCompany)),
                "regional-count-" + bill.toLowerCase() + "-" + from + "_" + to + ".xlsx");
    }

    /** Норматив выдачи путевых листов (§6.4) — по виду ПЛ. */
    @GetMapping("/waybill-norm")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','MINTRANS_ANALYST')")
    public tj.mintrans.epd.waybill.calc.report.WaybillNormReport waybillNorm(
            @RequestParam tj.mintrans.epd.waybill.domain.WaybillType type,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Short typeCompany) {
        return regionalReports.waybillNorm(type, from, to, typeCompany);
    }

    @GetMapping("/waybill-norm.xlsx")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','MINTRANS_ANALYST')")
    public ResponseEntity<byte[]> waybillNormXlsx(
            @RequestParam tj.mintrans.epd.waybill.domain.WaybillType type,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Short typeCompany) {
        return fileXlsx(xlsx.writeWaybillNorm(regionalReports.waybillNorm(type, from, to, typeCompany)),
                "waybill-norm-" + type.name().toLowerCase() + "-" + from + "_" + to + ".xlsx");
    }

    /** Сводный региональный отчёт в XLSX. */
    @GetMapping("/regional.xlsx")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','MINTRANS_ANALYST')")
    public ResponseEntity<byte[]> regionalXlsx(
            @RequestParam(defaultValue = "PASSENGER") String bill,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Short typeCompany) {
        RegionalReport report = "CARGO".equalsIgnoreCase(bill)
                ? regionalReports.cargoTransportation(from, to, typeCompany)
                : regionalReports.transportation(from, to, typeCompany);
        return fileXlsx(xlsx.writeRegional(report),
                "regional-transportation-" + bill.toLowerCase() + "-" + from + "_" + to + ".xlsx");
    }

    /**
     * Тренд пассажирооборота (млн пасс-км) по месяцам — KPI для панели администратора
     * (перенос легаси Admin\Charts\Ebus\PassengerVolumeController). Мультиарендность —
     * как у остальных методов этого контроллера (не переопределяется отдельным @PreAuthorize):
     * тенант видит свою область, платформенные роли — все организации.
     */
    @GetMapping("/passenger-volume-trend")
    public PassengerVolumeTrend passengerVolumeTrend(@RequestParam(defaultValue = "7") int months) {
        return regionalReports.passengerVolumeTrend(months);
    }

    /** Журнал предрейсового техконтроля (Дафтари қайди механик, тип 13). Инспектору доступен —
     *  надзор проверяет, действительно ли осмотры проводились. */
    @GetMapping("/journal/mechanic")
    @PreAuthorize("hasAnyRole('DISPATCHER','ACCOUNTANT','COMPANY_ADMIN','BRANCH_ADMIN','SYSTEM_ADMIN','MINTRANS_ANALYST','INSPECTOR')")
    public InspectionJournalService.MechanicJournal mechanicJournal(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String organizationRma) {
        return journals.mechanic(from, to, organizationRma);
    }

    // Тот же список ролей, что у JSON-варианта выше — без явного @PreAuthorize здесь
    // действовал класс-level список БЕЗ INSPECTOR, и кнопка «Скачать XLSX» в той же
    // вкладке, где JSON-журнал открывался нормально, тихо падала 403 (находка приёмки).
    @GetMapping("/journal/mechanic.xlsx")
    @PreAuthorize("hasAnyRole('DISPATCHER','ACCOUNTANT','COMPANY_ADMIN','BRANCH_ADMIN','SYSTEM_ADMIN','MINTRANS_ANALYST','INSPECTOR')")
    public ResponseEntity<byte[]> mechanicJournalXlsx(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String organizationRma) {
        return fileXlsx(xlsx.writeMechanicJournal(journals.mechanic(from, to, organizationRma)),
                "journal-mechanic-" + from + "_" + to + ".xlsx");
    }

    /** Журнал предрейсового/послерейсового медосмотра (Дафтари қайди духтӯр, тип 14). */
    @GetMapping("/journal/doctor")
    @PreAuthorize("hasAnyRole('DISPATCHER','ACCOUNTANT','COMPANY_ADMIN','BRANCH_ADMIN','SYSTEM_ADMIN','MINTRANS_ANALYST','INSPECTOR')")
    public InspectionJournalService.DoctorJournal doctorJournal(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String organizationRma) {
        return journals.doctor(from, to, organizationRma);
    }

    @GetMapping("/journal/doctor.xlsx")
    @PreAuthorize("hasAnyRole('DISPATCHER','ACCOUNTANT','COMPANY_ADMIN','BRANCH_ADMIN','SYSTEM_ADMIN','MINTRANS_ANALYST','INSPECTOR')")
    public ResponseEntity<byte[]> doctorJournalXlsx(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String organizationRma) {
        return fileXlsx(xlsx.writeDoctorJournal(journals.doctor(from, to, organizationRma)),
                "journal-doctor-" + from + "_" + to + ".xlsx");
    }

    private ResponseEntity<byte[]> fileXlsx(byte[] body, String name) {
        ContentDisposition cd = ContentDisposition.attachment()
                .filename(name, StandardCharsets.US_ASCII).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }

    private ResponseEntity<byte[]> xlsxResponse(WaybillReport report) {
        byte[] body = xlsx.write(report);
        ContentDisposition cd = ContentDisposition.attachment()
                .filename(ReportXlsxWriter.fileName(report), StandardCharsets.US_ASCII)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }

    /** Справочник типов разрезов (для выпадающего списка). */
    @GetMapping("/types")
    public List<Map<String, String>> types() {
        return java.util.Arrays.stream(ReportType.values())
                .map(t -> Map.of("code", t.name(), "label", t.label(), "grouping", t.grouping().name()))
                .toList();
    }
}
