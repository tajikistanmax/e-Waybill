package tj.mintrans.epd.waybill.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tj.mintrans.epd.waybill.calc.WaybillCalcAssembler;
import tj.mintrans.epd.waybill.calc.model.CargoCalcResult;
import tj.mintrans.epd.waybill.calc.model.FuelConsumption;
import tj.mintrans.epd.waybill.calc.model.PassengerCalcResult;
import tj.mintrans.epd.waybill.calc.model.PassengerMetrics;
import tj.mintrans.epd.waybill.calc.report.ReportGrouping;
import tj.mintrans.epd.waybill.calc.report.ReportRow;
import tj.mintrans.epd.waybill.calc.report.ReportType;
import tj.mintrans.epd.waybill.calc.report.WaybillReport;
import tj.mintrans.epd.waybill.config.TenantScope;
import tj.mintrans.epd.waybill.domain.Waybill;
import tj.mintrans.epd.waybill.domain.WaybillType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Типовые отчёты по путевым листам движком «Роҳхат»: период + группировка над
 * строками ПЛ → метрики по каждому листу ({@link WaybillCalcAssembler}) → агрегат.
 *
 * <p>Перенос {@code Report1CrudController} / {@code ReportWaybillCargoController}
 * (docs/spec/06-admin-reports-charts.md §1, §3). Мультиарендность — как в
 * {@link ReportService}: тенант видит только свою организацию.</p>
 */
@Service
public class WaybillReportService {

    private final WaybillPeriodScan scan;
    private final WaybillCalcAssembler assembler;
    private final TenantScope tenantScope;

    public WaybillReportService(WaybillPeriodScan scan, WaybillCalcAssembler assembler,
                                TenantScope tenantScope) {
        this.scan = scan;
        this.assembler = assembler;
        this.tenantScope = tenantScope;
    }

    /**
     * Дополнительный отбор строк отчёта (MIGRATION.md 6.7 — legacy {@code report_details}:
     * детализация по одному ТС {@code parking_id}; по водителю — симметрично).
     *
     * @param vehicleRegNumber госномер ТС (без учёта регистра/пробелов), {@code null} — все
     * @param driverRma        РМА водителя, {@code null} — все
     */
    public record Filter(String vehicleRegNumber, String driverRma, Set<WaybillType> forms) {
        public static final Filter NONE = new Filter(null, null, null);

        public static Filter of(String vehicleRegNumber, String driverRma) {
            return of(vehicleRegNumber, driverRma, null);
        }

        /**
         * @param forms бланк отчёта (legacy {@code report_bill}: bus / ebus / mbus / taxi, 2-Б / 5Б-БМ) —
         *              набор видов ПЛ; {@code null} или пусто — все виды своей группы (пасс./груз.)
         */
        public static Filter of(String vehicleRegNumber, String driverRma, Set<WaybillType> forms) {
            String v = norm(vehicleRegNumber);
            String d = norm(driverRma);
            Set<WaybillType> f = forms == null || forms.isEmpty() ? null : Set.copyOf(forms);
            return v == null && d == null && f == null ? NONE : new Filter(v, d, f);
        }

        boolean matches(Waybill wb) {
            if (forms != null && !forms.contains(wb.getWaybillType())) {
                return false;
            }
            if (vehicleRegNumber != null && !vehicleRegNumber.equals(norm(wb.getVehicleRegNumber()))) {
                return false;
            }
            return driverRma == null || driverRma.equals(norm(wb.getDriverRma()));
        }

        private static String norm(String s) {
            if (s == null) {
                return null;
            }
            String t = s.replace(" ", "").trim().toUpperCase();
            return t.isEmpty() ? null : t;
        }
    }

    /** Пассажирский отчёт (формы 1-А, 1-АД, 1-АДЕ, 3-С). */
    @Transactional(readOnly = true)
    public WaybillReport passenger(ReportType type, LocalDate from, LocalDate to, String organizationRma) {
        return passenger(type, from, to, organizationRma, Filter.NONE);
    }

    /** Пассажирский отчёт с отбором по ТС / водителю. */
    @Transactional(readOnly = true)
    public WaybillReport passenger(ReportType type, LocalDate from, LocalDate to, String organizationRma,
                                   Filter filter) {
        return build(type, from, to, organizationRma, false, filter);
    }

    /** Грузовой отчёт (формы 2-Б, 5Б-БМ). */
    @Transactional(readOnly = true)
    public WaybillReport cargo(ReportType type, LocalDate from, LocalDate to, String organizationRma) {
        return cargo(type, from, to, organizationRma, Filter.NONE);
    }

    /** Грузовой отчёт с отбором по ТС / водителю. */
    @Transactional(readOnly = true)
    public WaybillReport cargo(ReportType type, LocalDate from, LocalDate to, String organizationRma,
                               Filter filter) {
        return build(type, from, to, organizationRma, true, filter);
    }

    // ------------------------------------------------------------------

    private WaybillReport build(ReportType type, LocalDate from, LocalDate to,
                                String requestedOrg, boolean cargo, Filter filter) {
        Set<String> scope = resolveScope(requestedOrg);
        String org = scope == null ? null : String.join(",", scope);
        Filter f = filter == null ? Filter.NONE : filter;

        // Потоком по периоду (WaybillPeriodScan), а не findAll(): агрегируем строки, сущности не копим.
        Map<String, ReportRow> rows = new LinkedHashMap<>();
        scan.forEach(from, to, scope, wb -> {
            if (!(cargo ? isCargo(wb.getWaybillType()) : isPassenger(wb.getWaybillType()))) {
                return;
            }
            if (!countsInReports(wb)) {
                return;
            }
            if (!f.matches(wb)) {
                return;
            }
            Contribution c = contribution(wb, cargo);
            String key = groupKey(type.grouping(), wb, c);
            String label = groupLabel(type.grouping(), wb, c, key);
            ReportRow row = rows.computeIfAbsent(key, k -> ReportRow.zero(k, label));
            rows.put(key, row.plus(c.laps, c.distanceKm, c.routeDistanceKm, c.turnover, c.passengers,
                    c.normLiters, c.givenLiters, c.revenue, c.kassa, c.salary,
                    c.workDays, c.workHours, c.transportWork, c.trips));
        });

        List<ReportRow> ordered = new ArrayList<>(rows.values());
        ordered.sort((a, b) -> a.key().compareToIgnoreCase(b.key()));
        ReportRow totals = ordered.stream().reduce(ReportRow.zero("TOTAL", "ИТОГО"), ReportRow::merge);

        return new WaybillReport(type, type.label(), from, to, org, ordered, totals);
    }

    private record Contribution(long laps, double distanceKm, double routeDistanceKm, double turnover,
                                double passengers, double normLiters, double givenLiters,
                                BigDecimal revenue, BigDecimal kassa, BigDecimal salary,
                                String brand, int workDays, double workHours,
                                double transportWork, double trips) {
    }

    private Contribution contribution(Waybill wb, boolean cargo) {
        WaybillCalcAssembler.View view = assembler.calculate(wb, WaybillCalcAssembler.Supplement.empty());
        String brand = brandOf(wb);
        double hours = view.workMinutes() / 60d;
        if (cargo && view.cargo() != null) {
            // Грузовые колонки legacy cargo2b/type_1: рӯзи корӣ, соат, гардиши бор (P), рейсҳо (Z).
            CargoCalcResult r = view.cargo();
            double given = r.fuels().stream().mapToDouble(FuelConsumption::given).sum();
            return new Contribution(0L, r.distanceKm(), 0d, 0d, 0d, r.totalNormLiters(), given,
                    BigDecimal.ZERO, BigDecimal.ZERO, r.salary().salary(), brand,
                    view.workDays(), hours, r.transportWork(), r.trips());
        }
        if (view.passenger() != null) {
            PassengerCalcResult r = view.passenger();
            PassengerMetrics m = r.passengerMetrics();
            double given = r.fuels().stream().mapToDouble(FuelConsumption::given).sum();
            return new Contribution(m.laps(), m.totalDistanceKm(), m.routeDistanceKm(),
                    m.passengerTurnover(), m.passengerCount(), r.totalNormLiters(), given,
                    m.earning(), m.kassa(), r.salary().salary(), brand,
                    Math.max(1, m.workDays()), m.workTimeMinutes() / 60d, 0d, 0d);
        }
        return new Contribution(0L, r0(wb), 0d, 0d, 0d, 0d, 0d,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, brand, 1, 0d, 0d, 0d);
    }

    private static double r0(Waybill wb) {
        if (wb.getOdometerExit() == null || wb.getOdometerEntry() == null) {
            return 0d;
        }
        return Math.max(0, wb.getOdometerEntry() - wb.getOdometerExit());
    }

    private static String groupKey(ReportGrouping g, Waybill wb, Contribution c) {
        return switch (g) {
            case VEHICLE -> blankTo(wb.getVehicleRegNumber(), "—");
            case ROUTE -> blankTo(wb.getRoute(), "—");
            case BRAND -> blankTo(c.brand, "—");
            case DRIVER -> blankTo(wb.getDriverRma(), "—");
            case NONE -> "TOTAL";
            case PER_WAYBILL -> wb.getNumber() != null ? wb.getNumber() : wb.getId().toString();
        };
    }

    private static String groupLabel(ReportGrouping g, Waybill wb, Contribution c, String key) {
        return switch (g) {
            case DRIVER -> {
                String name = snapshotString(wb.getDriverSnapshot(), "fullName");
                yield name != null ? name : key;
            }
            case NONE -> "ИТОГО по предприятию";
            default -> key;
        };
    }

    private static String brandOf(Waybill wb) {
        return snapshotString(wb.getVehicleSnapshot(), "brand");
    }

    private static String snapshotString(Map<String, Object> snapshot, String field) {
        if (snapshot == null || snapshot.get(field) == null) {
            return null;
        }
        String v = snapshot.get(field).toString();
        return v.isBlank() ? null : v;
    }

    /**
     * Лист попадает в типовой отчёт, только если он выдан как документ (есть номер — лист прошёл
     * допуск и оплату) и не аннулирован. Аннулирование в старой платформе — удаление записи, такие
     * листы в её отчётах не участвуют.
     *
     * <p>До 23.09.2026 отчёт брал все листы периода подряд: аннулированные, черновики и недопущенные
     * врачом/механиком. В «Реестре путевых листов» они выходили строками с внутренним
     * идентификатором вместо номера, а в «Заработке водителей» каждому начислялась надбавка за
     * класс — водитель «зарабатывал» на рейсе, которого не было (находка живой проверки).</p>
     */
    static boolean countsInReports(Waybill wb) {
        return wb.getNumber() != null && !wb.getNumber().isBlank()
                && wb.getStatus() != tj.mintrans.epd.waybill.domain.WaybillStatus.CANCELLED;
    }

    private static boolean isPassenger(WaybillType t) {
        return t == WaybillType.WB_BUS || t == WaybillType.WB_TROLLEYBUS || t == WaybillType.WB_MINIBUS
                || t == WaybillType.WB_CAR || t == WaybillType.WB_TAXI || t == WaybillType.WB_PAX_INTL;
    }

    private static boolean isCargo(WaybillType t) {
        return t == WaybillType.WB_TRUCK || t == WaybillType.WB_TRUCK_INTL
                || t == WaybillType.WB_SPECIAL || t == WaybillType.WB_DANGEROUS;
    }

    /**
     * Набор организаций отчёта: {@code null} — все (платформенная роль без фильтра);
     * иначе — область тенанта (компания + филиалы; «__none__» → пусто = нет доступа)
     * либо запрошенная организация для платформы.
     */
    private Set<String> resolveScope(String requested) {
        if (tenantScope.isBounded()) {
            Set<String> s = tenantScope.rmas();
            if (s == null || s.contains("__none__")) {
                return Set.of();
            }
            // Перевозчик с филиалами выбирает одну из СВОИХ организаций (legacy waybillcargo/form: «Корхона»);
            // раньше выбор игнорировался и отчёт всегда шёл по всей области.
            if (requested != null && !requested.isBlank() && s.contains(requested.trim())) {
                return Set.of(requested.trim());
            }
            return s;
        }
        return requested == null || requested.isBlank() ? null : Set.of(requested.trim());
    }

    private static String blankTo(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v;
    }
}
