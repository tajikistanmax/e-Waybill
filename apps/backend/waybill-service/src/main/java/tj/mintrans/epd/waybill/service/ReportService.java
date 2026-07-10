package tj.mintrans.epd.waybill.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tj.mintrans.epd.waybill.config.CurrentUser;
import tj.mintrans.epd.waybill.domain.FuelRecord;
import tj.mintrans.epd.waybill.domain.Waybill;
import tj.mintrans.epd.waybill.domain.WaybillStatus;
import tj.mintrans.epd.waybill.repository.FuelRecordRepository;
import tj.mintrans.epd.waybill.repository.WaybillRepository;
import tj.mintrans.epd.waybill.repository.WorkDayRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Отчёты по путевым листам (MVP: сборка в памяти из findAll — данных немного,
 * простота важнее). Фильтр периода — по created_at; мультиарендность — не-админ
 * видит только свою организацию (claim organization_rma).
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

    private final WaybillRepository waybills;
    private final WorkDayRepository workDays;
    private final FuelRecordRepository fuelRecords;
    private final CurrentUser currentUser;

    public ReportService(WaybillRepository waybills, WorkDayRepository workDays,
                         FuelRecordRepository fuelRecords, CurrentUser currentUser) {
        this.waybills = waybills;
        this.workDays = workDays;
        this.fuelRecords = fuelRecords;
        this.currentUser = currentUser;
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
        var list = load(from, to, organizationRma);
        long completed = list.stream().filter(wb -> wb.getStatus() == WaybillStatus.COMPLETED).count();
        long cancelled = list.stream().filter(wb -> wb.getStatus() == WaybillStatus.CANCELLED).count();
        long active = list.stream().filter(wb -> wb.getStatus() == WaybillStatus.ACTIVE).count();
        long distanceKm = list.stream()
                .filter(wb -> wb.getStatus() == WaybillStatus.COMPLETED)
                .mapToLong(ReportService::distanceOf)
                .sum();

        var ids = list.stream().map(Waybill::getId).collect(Collectors.toSet());
        BigDecimal fuelGiven = fuelRecords.findAll().stream()
                .filter(fr -> ids.contains(fr.getWaybillId()))
                .map(FuelRecord::getFuelGiven)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal revenue = workDays.findAll().stream()
                .filter(wd -> ids.contains(wd.getWaybillId()))
                .map(wd -> wd.getRevenue())
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Long> byStatus = list.stream().collect(Collectors.groupingBy(
                wb -> wb.getStatus().name(), LinkedHashMap::new, Collectors.counting()));
        Map<String, Long> byType = list.stream().collect(Collectors.groupingBy(
                wb -> wb.getWaybillType().name(), LinkedHashMap::new, Collectors.counting()));

        return new SummaryReport(new Period(from, to),
                new Totals(list.size(), completed, cancelled, active, distanceKm, fuelGiven, revenue),
                byStatus, byType);
    }

    /** Журнал диспетчера: путевые листы, созданные в указанную дату. */
    @Transactional(readOnly = true)
    public List<JournalRow> dispatcherJournal(LocalDate date, String organizationRma) {
        return load(date, date, organizationRma).stream()
                .sorted(Comparator.comparing(Waybill::getCreatedAt))
                .map(wb -> new JournalRow(
                        wb.getNumber(),
                        wb.getWaybillType().name(),
                        wb.getVehicleRegNumber(),
                        driverName(wb),
                        wb.getStatus().name(),
                        wb.getValidFrom(),
                        wb.getValidTo(),
                        wb.getOdometerExit(),
                        wb.getOdometerEntry()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<DriverRow> byDriver(LocalDate from, LocalDate to, String organizationRma) {
        var byDriver = load(from, to, organizationRma).stream()
                .collect(Collectors.groupingBy(Waybill::getDriverRma, LinkedHashMap::new, Collectors.toList()));
        return byDriver.entrySet().stream()
                .map(e -> new DriverRow(
                        e.getKey(),
                        e.getValue().stream().map(ReportService::driverName)
                                .filter(java.util.Objects::nonNull).findFirst().orElse(null),
                        e.getValue().size(),
                        completedCount(e.getValue()),
                        completedDistance(e.getValue())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<VehicleRow> byVehicle(LocalDate from, LocalDate to, String organizationRma) {
        var byVehicle = load(from, to, organizationRma).stream()
                .collect(Collectors.groupingBy(Waybill::getVehicleRegNumber, LinkedHashMap::new, Collectors.toList()));
        return byVehicle.entrySet().stream()
                .map(e -> new VehicleRow(
                        e.getKey(),
                        e.getValue().size(),
                        completedCount(e.getValue()),
                        completedDistance(e.getValue())))
                .toList();
    }

    /** Топливо по видам: выдано и остаток при возвращении (по ПЛ периода). */
    @Transactional(readOnly = true)
    public List<FuelRow> fuel(LocalDate from, LocalDate to, String organizationRma) {
        Set<UUID> ids = load(from, to, organizationRma).stream()
                .map(Waybill::getId).collect(Collectors.toSet());
        var byType = fuelRecords.findAll().stream()
                .filter(fr -> ids.contains(fr.getWaybillId()))
                .collect(Collectors.groupingBy(FuelRecord::getFuelType,
                        java.util.TreeMap::new, Collectors.toList()));
        return byType.entrySet().stream()
                .map(e -> new FuelRow(
                        e.getKey(),
                        FUEL_NAMES.getOrDefault(e.getKey(), "Неизвестно"),
                        sum(e.getValue(), FuelRecord::getFuelGiven),
                        sum(e.getValue(), FuelRecord::getRemainEntry)))
                .toList();
    }

    // ------------------------------------------------------------------ вспомогательные

    /**
     * ПЛ за период (по created_at) с учётом мультиарендности: не-админ видит
     * только свою организацию (пришедший organizationRma игнорируется;
     * нет claim — пустой список).
     */
    private List<Waybill> load(LocalDate from, LocalDate to, String requestedOrganizationRma) {
        String org;
        if (currentUser.isTenantScoped()) {
            org = currentUser.organizationRma().orElse(null);
            if (org == null) {
                return List.of();
            }
        } else {
            org = requestedOrganizationRma;
        }
        return waybills.findAll().stream()
                .filter(wb -> org == null || org.equals(wb.getOrganizationRma()))
                .filter(wb -> {
                    var created = wb.getCreatedAt().toLocalDate();
                    return !created.isBefore(from) && !created.isAfter(to);
                })
                .toList();
    }

    private static long distanceOf(Waybill wb) {
        if (wb.getOdometerEntry() == null || wb.getOdometerExit() == null) {
            return 0;
        }
        return Math.max(0, wb.getOdometerEntry() - wb.getOdometerExit());
    }

    private static long completedCount(List<Waybill> list) {
        return list.stream().filter(wb -> wb.getStatus() == WaybillStatus.COMPLETED).count();
    }

    private static long completedDistance(List<Waybill> list) {
        return list.stream()
                .filter(wb -> wb.getStatus() == WaybillStatus.COMPLETED)
                .mapToLong(ReportService::distanceOf)
                .sum();
    }

    private static String driverName(Waybill wb) {
        var snapshot = wb.getDriverSnapshot();
        if (snapshot == null || snapshot.get("fullName") == null) {
            return null;
        }
        return snapshot.get("fullName").toString();
    }

    private static BigDecimal sum(List<FuelRecord> records,
                                  java.util.function.Function<FuelRecord, BigDecimal> getter) {
        return records.stream()
                .map(getter)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
