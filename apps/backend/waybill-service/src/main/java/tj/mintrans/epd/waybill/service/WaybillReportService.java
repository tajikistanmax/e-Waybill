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
import tj.mintrans.epd.waybill.repository.WaybillRepository;

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

    private final WaybillRepository waybills;
    private final WaybillCalcAssembler assembler;
    private final TenantScope tenantScope;

    public WaybillReportService(WaybillRepository waybills, WaybillCalcAssembler assembler,
                                TenantScope tenantScope) {
        this.waybills = waybills;
        this.assembler = assembler;
        this.tenantScope = tenantScope;
    }

    /** Пассажирский отчёт (формы 1-А, 1-АД, 1-АДЕ, 3-С). */
    @Transactional(readOnly = true)
    public WaybillReport passenger(ReportType type, LocalDate from, LocalDate to, String organizationRma) {
        return build(type, from, to, organizationRma, false);
    }

    /** Грузовой отчёт (формы 2-Б, 5Б-БМ). */
    @Transactional(readOnly = true)
    public WaybillReport cargo(ReportType type, LocalDate from, LocalDate to, String organizationRma) {
        return build(type, from, to, organizationRma, true);
    }

    // ------------------------------------------------------------------

    private WaybillReport build(ReportType type, LocalDate from, LocalDate to,
                                String requestedOrg, boolean cargo) {
        Set<String> scope = resolveScope(requestedOrg);
        String org = scope == null ? null : String.join(",", scope);
        List<Waybill> list = waybills.findAll().stream()
                .filter(wb -> scope == null || scope.contains(wb.getOrganizationRma()))
                .filter(wb -> cargo ? isCargo(wb.getWaybillType()) : isPassenger(wb.getWaybillType()))
                .filter(wb -> inPeriod(wb, from, to))
                .toList();

        Map<String, ReportRow> rows = new LinkedHashMap<>();
        for (Waybill wb : list) {
            Contribution c = contribution(wb, cargo);
            String key = groupKey(type.grouping(), wb, c);
            String label = groupLabel(type.grouping(), wb, c, key);
            ReportRow row = rows.computeIfAbsent(key, k -> ReportRow.zero(k, label));
            rows.put(key, row.plus(c.laps, c.distanceKm, c.routeDistanceKm, c.turnover, c.passengers,
                    c.normLiters, c.givenLiters, c.revenue, c.kassa, c.salary));
        }

        List<ReportRow> ordered = new ArrayList<>(rows.values());
        ordered.sort((a, b) -> a.key().compareToIgnoreCase(b.key()));
        ReportRow totals = ordered.stream().reduce(ReportRow.zero("TOTAL", "ИТОГО"), ReportRow::merge);

        return new WaybillReport(type, type.label(), from, to, org, ordered, totals);
    }

    private record Contribution(long laps, double distanceKm, double routeDistanceKm, double turnover,
                                double passengers, double normLiters, double givenLiters,
                                BigDecimal revenue, BigDecimal kassa, BigDecimal salary,
                                String brand) {
    }

    private Contribution contribution(Waybill wb, boolean cargo) {
        WaybillCalcAssembler.View view = assembler.calculate(wb, WaybillCalcAssembler.Supplement.empty());
        String brand = brandOf(wb);
        if (cargo && view.cargo() != null) {
            CargoCalcResult r = view.cargo();
            double given = r.fuels().stream().mapToDouble(FuelConsumption::given).sum();
            return new Contribution(0L, r.distanceKm(), 0d, 0d, 0d, r.totalNormLiters(), given,
                    BigDecimal.ZERO, BigDecimal.ZERO, r.salary().salary(), brand);
        }
        if (view.passenger() != null) {
            PassengerCalcResult r = view.passenger();
            PassengerMetrics m = r.passengerMetrics();
            double given = r.fuels().stream().mapToDouble(FuelConsumption::given).sum();
            return new Contribution(m.laps(), m.totalDistanceKm(), m.routeDistanceKm(),
                    m.passengerTurnover(), m.passengerCount(), r.totalNormLiters(), given,
                    m.earning(), m.kassa(), r.salary().salary(), brand);
        }
        return new Contribution(0L, r0(wb), 0d, 0d, 0d, 0d, 0d,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, brand);
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

    private static boolean isPassenger(WaybillType t) {
        return t == WaybillType.WB_BUS || t == WaybillType.WB_TROLLEYBUS || t == WaybillType.WB_MINIBUS
                || t == WaybillType.WB_CAR || t == WaybillType.WB_TAXI || t == WaybillType.WB_PAX_INTL;
    }

    private static boolean isCargo(WaybillType t) {
        return t == WaybillType.WB_TRUCK || t == WaybillType.WB_TRUCK_INTL
                || t == WaybillType.WB_SPECIAL || t == WaybillType.WB_DANGEROUS;
    }

    private static boolean inPeriod(Waybill wb, LocalDate from, LocalDate to) {
        if (wb.getCreatedAt() == null) {
            return false;
        }
        LocalDate d = wb.getCreatedAt().toLocalDate();
        return !d.isBefore(from) && !d.isAfter(to);
    }

    /**
     * Набор организаций отчёта: {@code null} — все (платформенная роль без фильтра);
     * иначе — область тенанта (компания + филиалы) либо запрошенная организация для платформы.
     */
    private Set<String> resolveScope(String requested) {
        if (tenantScope.isBounded()) {
            return tenantScope.rmas();
        }
        return requested == null || requested.isBlank() ? null : Set.of(requested.trim());
    }

    private static String blankTo(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v;
    }
}
