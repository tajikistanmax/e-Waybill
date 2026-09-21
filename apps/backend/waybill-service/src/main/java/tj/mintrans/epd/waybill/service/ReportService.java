package tj.mintrans.epd.waybill.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tj.mintrans.epd.waybill.config.TenantScope;
import tj.mintrans.epd.waybill.domain.Waybill;
import tj.mintrans.epd.waybill.domain.WaybillStatus;
import tj.mintrans.epd.waybill.repository.FuelRecordRepository;
import tj.mintrans.epd.waybill.repository.WorkDayRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Отчёты по путевым листам. Период — по created_at; мультиарендность — не-админ видит
 * только свою область (компания + филиалы), платформенная роль — все либо запрошенную организацию.
 *
 * <p>С 21.09.2026 — без {@code findAll()}: ПЛ читаются потоком по периоду
 * ({@link WaybillPeriodScan}), суммы топлива/выручки считает БД. После миграции Ф5
 * (≈2,3 млн архивных ПЛ) прежняя сборка «всё в память» роняла сервис в OutOfMemoryError.</p>
 */
@Service
public class ReportService {

    /** Справочник видов топлива (раздел 8 legacy-спеки). */
    private static final Map<Short, String> FUEL_NAMES = Map.of(
            (short) 1, "Бензин",
            (short) 2, "Солярка",
            (short) 3, "Газ сжиженный",
            (short) 4, "Газ природный",
            (short) 5, "Электро");

    private final WaybillPeriodScan scan;
    private final WorkDayRepository workDays;
    private final FuelRecordRepository fuelRecords;
    private final TenantScope tenantScope;

    public ReportService(WaybillPeriodScan scan, WorkDayRepository workDays,
                         FuelRecordRepository fuelRecords, TenantScope tenantScope) {
        this.scan = scan;
        this.workDays = workDays;
        this.fuelRecords = fuelRecords;
        this.tenantScope = tenantScope;
    }

    // ------------------------------------------------------------------ DTO

    public record Period(LocalDate from, LocalDate to) {
    }

    public record Totals(long waybills, long completed, long cancelled, long active,
                         long distanceKm, BigDecimal fuelGivenLiters, BigDecimal revenue) {
    }

    public record SummaryReport(Period period, Totals totals,
                                Map<String, Long> byStatus, Map<String, Long> byType) {
    }

    public record JournalRow(String number, String waybillType, String vehicleRegNumber,
                             String driverName, String status, OffsetDateTime validFrom,
                             OffsetDateTime validTo, Integer odometerExit, Integer odometerEntry) {
    }

    public record DriverRow(String driverRma, String fullName, long waybills,
                            long completed, long distanceKm) {
    }

    public record VehicleRow(String vehicleRegNumber, long waybills,
                             long completed, long distanceKm) {
    }

    public record FuelRow(short fuelType, String fuelName, BigDecimal given, BigDecimal remainEnd) {
    }

    // ------------------------------------------------------------------ отчёты

    @Transactional(readOnly = true)
    public SummaryReport summary(LocalDate from, LocalDate to, String organizationRma) {
        Set<String> scope = resolveScope(organizationRma);
        long[] c = new long[5]; // 0 total, 1 completed, 2 cancelled, 3 active, 4 distance
        Map<String, Long> byStatus = new LinkedHashMap<>();
        Map<String, Long> byType = new LinkedHashMap<>();
        if (!noAccess(scope)) {
            scan.forEachAll(from, to, scope, wb -> {   // счётная сводка — без лимита строк
                c[0]++;
                if (wb.getStatus() == WaybillStatus.COMPLETED) {
                    c[1]++;
                    c[4] += distanceOf(wb);
                } else if (wb.getStatus() == WaybillStatus.CANCELLED) {
                    c[2]++;
                } else if (wb.getStatus() == WaybillStatus.ACTIVE) {
                    c[3]++;
                }
                byStatus.merge(wb.getStatus().name(), 1L, Long::sum);
                byType.merge(wb.getWaybillType().name(), 1L, Long::sum);
            });
        }
        BigDecimal fuelGiven = BigDecimal.ZERO;
        BigDecimal revenue = BigDecimal.ZERO;
        if (!noAccess(scope)) {
            OffsetDateTime lo = WaybillPeriodScan.lower(from);
            OffsetDateTime hi = WaybillPeriodScan.upper(to);
            fuelGiven = nz(scope == null
                    ? fuelRecords.sumFuelGivenInPeriod(lo, hi)
                    : fuelRecords.sumFuelGivenInPeriodForOrganizations(scope, lo, hi));
            revenue = nz(scope == null
                    ? workDays.sumRevenueInPeriod(lo, hi)
                    : workDays.sumRevenueInPeriodForOrganizations(scope, lo, hi));
        }
        return new SummaryReport(new Period(from, to),
                new Totals(c[0], c[1], c[2], c[3], c[4], fuelGiven, revenue),
                byStatus, byType);
    }

    /** Журнал диспетчера: путевые листы, созданные в указанную дату (в порядке создания). */
    @Transactional(readOnly = true)
    public List<JournalRow> dispatcherJournal(LocalDate date, String organizationRma) {
        Set<String> scope = resolveScope(organizationRma);
        List<JournalRow> rows = new ArrayList<>();
        if (noAccess(scope)) {
            return rows;
        }
        scan.forEachAll(date, date, scope, wb -> rows.add(new JournalRow(
                wb.getNumber(),
                wb.getWaybillType().name(),
                wb.getVehicleRegNumber(),
                driverName(wb),
                wb.getStatus().name(),
                wb.getValidFrom(),
                wb.getValidTo(),
                wb.getOdometerExit(),
                wb.getOdometerEntry())));
        return rows;
    }

    /** Накопитель по ключу группировки (водитель / ТС): листов, завершено, пробег завершённых. */
    private static final class Agg {
        long waybills;
        long completed;
        long distance;
        String label;

        void add(Waybill wb, String label) {
            waybills++;
            if (wb.getStatus() == WaybillStatus.COMPLETED) {
                completed++;
                distance += distanceOf(wb);
            }
            if (this.label == null && label != null) {
                this.label = label;
            }
        }
    }

    @Transactional(readOnly = true)
    public List<DriverRow> byDriver(LocalDate from, LocalDate to, String organizationRma) {
        Set<String> scope = resolveScope(organizationRma);
        Map<String, Agg> byDriver = new LinkedHashMap<>();
        if (!noAccess(scope)) {
            scan.forEachAll(from, to, scope, wb ->
                    byDriver.computeIfAbsent(wb.getDriverRma(), k -> new Agg()).add(wb, driverName(wb)));
        }
        return byDriver.entrySet().stream()
                .map(e -> new DriverRow(e.getKey(), e.getValue().label,
                        e.getValue().waybills, e.getValue().completed, e.getValue().distance))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<VehicleRow> byVehicle(LocalDate from, LocalDate to, String organizationRma) {
        Set<String> scope = resolveScope(organizationRma);
        Map<String, Agg> byVehicle = new LinkedHashMap<>();
        if (!noAccess(scope)) {
            scan.forEachAll(from, to, scope, wb ->
                    byVehicle.computeIfAbsent(wb.getVehicleRegNumber(), k -> new Agg()).add(wb, null));
        }
        return byVehicle.entrySet().stream()
                .map(e -> new VehicleRow(e.getKey(),
                        e.getValue().waybills, e.getValue().completed, e.getValue().distance))
                .toList();
    }

    /** Топливо по видам: выдано и остаток при возвращении (по ПЛ периода) — агрегат в БД. */
    @Transactional(readOnly = true)
    public List<FuelRow> fuel(LocalDate from, LocalDate to, String organizationRma) {
        Set<String> scope = resolveScope(organizationRma);
        if (noAccess(scope)) {
            return List.of();
        }
        OffsetDateTime lo = WaybillPeriodScan.lower(from);
        OffsetDateTime hi = WaybillPeriodScan.upper(to);
        List<Object[]> rows = scope == null
                ? fuelRecords.sumByFuelTypeInPeriod(lo, hi)
                : fuelRecords.sumByFuelTypeInPeriodForOrganizations(scope, lo, hi);
        List<FuelRow> out = new ArrayList<>();
        for (Object[] r : rows) {
            short type = ((Number) r[0]).shortValue();
            out.add(new FuelRow(type, FUEL_NAMES.getOrDefault(type, "Неизвестно"),
                    nz((BigDecimal) r[1]), nz((BigDecimal) r[2])));
        }
        return out;
    }

    // ------------------------------------------------------------------ вспомогательные

    /**
     * Область отчёта: {@code null} — все организации (платформенная роль без фильтра);
     * иначе — область тенанта (компания + филиалы; пустая/«__none__» = нет доступа)
     * либо запрошенная организация для платформы.
     */
    private Set<String> resolveScope(String requestedOrganizationRma) {
        if (tenantScope.isBounded()) {
            Set<String> scope = tenantScope.rmas();
            return scope == null || scope.contains("__none__") ? Set.of() : scope;
        }
        return requestedOrganizationRma == null || requestedOrganizationRma.isBlank()
                ? null : Set.of(requestedOrganizationRma);
    }

    private static boolean noAccess(Set<String> scope) {
        return scope != null && scope.isEmpty();
    }

    private static long distanceOf(Waybill wb) {
        if (wb.getOdometerEntry() == null || wb.getOdometerExit() == null) {
            return 0;
        }
        return Math.max(0, wb.getOdometerEntry() - wb.getOdometerExit());
    }

    private static String driverName(Waybill wb) {
        var snapshot = wb.getDriverSnapshot();
        if (snapshot == null || snapshot.get("fullName") == null) {
            return null;
        }
        return snapshot.get("fullName").toString();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
