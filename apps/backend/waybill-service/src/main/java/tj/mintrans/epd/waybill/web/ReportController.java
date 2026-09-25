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
    private final tj.mintrans.epd.waybill.service.ActivityReportService activity;

    public ReportController(ReportService reports, WaybillReportService typedReports,
                            RegionalReportService regionalReports, InspectionJournalService journals,
                            ReportXlsxWriter xlsx, tj.mintrans.epd.waybill.service.ActivityReportService activity) {
        this.reports = reports;
        this.typedReports = typedReports;
        this.regionalReports = regionalReports;
        this.journals = journals;
        this.xlsx = xlsx;
        this.activity = activity;
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

    /**
     * Активность ТС / водителей за период — число ПЛ выбранных видов по госномеру или РМА водителя
     * (MIGRATION.md 8.5/8.6: фильтры legacy-реестров «активные / без ПЛ / ровно N за период»).
     *
     * @param by    VEHICLE | DRIVER
     * @param types виды ПЛ через запятую (WB_CAR,WB_TAXI …); не переданы — все виды
     */
    @GetMapping("/activity")
    public List<tj.mintrans.epd.waybill.service.ActivityReportService.Row> activity(
            @RequestParam tj.mintrans.epd.waybill.service.ActivityReportService.By by,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) List<tj.mintrans.epd.waybill.domain.WaybillType> types,
            @RequestParam(required = false) String organizationRma) {
        return activity.activity(by, from, to, types, organizationRma);
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
            @RequestParam(required = false) String driverRma,
            @RequestParam(required = false) String bill) {
        return typedReports.passenger(type, from, to, organizationRma,
                WaybillReportService.Filter.of(vehicleRegNumber, driverRma, billForms(bill)));
    }

    /** Грузовой отчёт (формы 2-Б, 5Б-БМ), тип разреза — {@code ?type=}; отбор по ТС / водителю — как у пассажирского. */
    @GetMapping("/cargo")
    public WaybillReport cargo(
            @RequestParam ReportType type,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String organizationRma,
            @RequestParam(required = false) String vehicleRegNumber,
            @RequestParam(required = false) String driverRma,
            @RequestParam(required = false) String bill) {
        return typedReports.cargo(type, from, to, organizationRma,
                WaybillReportService.Filter.of(vehicleRegNumber, driverRma, billForms(bill)));
    }

    /** Пассажирский отчёт в XLSX. */
    @GetMapping("/passenger.xlsx")
    public ResponseEntity<byte[]> passengerXlsx(
            @RequestParam ReportType type,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String organizationRma,
            @RequestParam(required = false) String vehicleRegNumber,
            @RequestParam(required = false) String driverRma,
            @RequestParam(required = false) String bill) {
        return xlsxResponse(typedReports.passenger(type, from, to, organizationRma,
                WaybillReportService.Filter.of(vehicleRegNumber, driverRma, billForms(bill))));
    }

    /** Грузовой отчёт в XLSX. */
    @GetMapping("/cargo.xlsx")
    public ResponseEntity<byte[]> cargoXlsx(
            @RequestParam ReportType type,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String organizationRma,
            @RequestParam(required = false) String vehicleRegNumber,
            @RequestParam(required = false) String driverRma,
            @RequestParam(required = false) String bill) {
        return xlsxResponse(typedReports.cargo(type, from, to, organizationRma,
                WaybillReportService.Filter.of(vehicleRegNumber, driverRma, billForms(bill))));
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
            @RequestParam(required = false) Short typeCompany,
            // Отбор конкретной формы ПЛ внутри разреза (Шакли 3-С / 1-А / 2-Б / 5Б-БМ), как в
            // старой платформе; не передан — все формы разреза.
            @RequestParam(required = false) tj.mintrans.epd.waybill.domain.WaybillType type) {
        return regionalReports.transportation(bill, from, to, typeCompany, type);
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
            @RequestParam(required = false) Short typeCompany,
            @RequestParam(required = false) tj.mintrans.epd.waybill.domain.WaybillType type) {
        RegionalReport report = regionalReports.transportation(bill, from, to, typeCompany, type);
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
    public PassengerVolumeTrend passengerVolumeTrend(
            @RequestParam(defaultValue = "7") int months,
            @RequestParam(required = false) tj.mintrans.epd.waybill.domain.WaybillType type) {
        // MIGRATION.md 6.8: legacy dashboard/ebus — только троллейбус; ?type= сужает до одного вида.
        if (type != null && type != tj.mintrans.epd.waybill.domain.WaybillType.WB_BUS
                && type != tj.mintrans.epd.waybill.domain.WaybillType.WB_TROLLEYBUS) {
            throw new tj.mintrans.epd.waybill.web.error.ApiErrors.UnprocessableException(
                    "Тренд строится только для автобусов (WB_BUS) и троллейбусов (WB_TROLLEYBUS)");
        }
        return regionalReports.passengerVolumeTrend(months, type == null ? null : java.util.Set.of(type));
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

    /**
     * Бланк отчёта — legacy {@code report_bill} формы отчёта (Report1Crud / ReportWaybillCargo):
     * отчёт строится по одному бланку, а не по всем пассажирским/грузовым вместе (сверка 25.09, D1).
     * {@code bus} 1-АД, {@code ebus} троллейбус, {@code mbus} 1-А, {@code taxi} 3-С (легковой и такси),
     * {@code pax_intl} 4-МБМ; {@code cargo2b} 2-Б, {@code cargo5bbm} 5Б-БМ, {@code special}, {@code dangerous}.
     * Пусто / {@code all} — все виды группы; неизвестное значение — 400.
     */
    static java.util.Set<tj.mintrans.epd.waybill.domain.WaybillType> billForms(String bill) {
        if (bill == null || bill.isBlank() || "all".equalsIgnoreCase(bill.trim())) {
            return null;
        }
        return switch (bill.trim().toLowerCase()) {
            case "bus" -> java.util.EnumSet.of(tj.mintrans.epd.waybill.domain.WaybillType.WB_BUS);
            case "ebus" -> java.util.EnumSet.of(tj.mintrans.epd.waybill.domain.WaybillType.WB_TROLLEYBUS);
            case "mbus" -> java.util.EnumSet.of(tj.mintrans.epd.waybill.domain.WaybillType.WB_MINIBUS);
            case "taxi" -> java.util.EnumSet.of(tj.mintrans.epd.waybill.domain.WaybillType.WB_CAR,
                    tj.mintrans.epd.waybill.domain.WaybillType.WB_TAXI);
            case "pax_intl" -> java.util.EnumSet.of(tj.mintrans.epd.waybill.domain.WaybillType.WB_PAX_INTL);
            case "cargo2b" -> java.util.EnumSet.of(tj.mintrans.epd.waybill.domain.WaybillType.WB_TRUCK);
            case "cargo5bbm" -> java.util.EnumSet.of(tj.mintrans.epd.waybill.domain.WaybillType.WB_TRUCK_INTL);
            case "special" -> java.util.EnumSet.of(tj.mintrans.epd.waybill.domain.WaybillType.WB_SPECIAL);
            case "dangerous" -> java.util.EnumSet.of(tj.mintrans.epd.waybill.domain.WaybillType.WB_DANGEROUS);
            default -> throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "Неизвестный бланк отчёта: " + bill);
        };
    }
}
