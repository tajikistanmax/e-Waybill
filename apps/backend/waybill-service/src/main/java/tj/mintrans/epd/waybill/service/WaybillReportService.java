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
    private tj.mintrans.epd.waybill.repository.WorkDayRepository workDays;
    private tj.mintrans.epd.waybill.client.MasterDataClient masterData;

    /** Справочники маршрутов и марок — подписи групп «Хатсайр» / «Тамға» (сверка 25.09, D13). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setMasterData(tj.mintrans.epd.waybill.client.MasterDataClient masterData) {
        this.masterData = masterData;
    }

    /** Рабочие дни — для выезда/возврата в построчных отчётах (сверка 25.09, D5). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setWorkDays(tj.mintrans.epd.waybill.repository.WorkDayRepository workDays) {
        this.workDays = workDays;
    }

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

        // Многодневные 1-А / 3-С относятся к периоду по датам рабочих дней, как в legacy (Report1CrudController:
        // mbus / taxi — whereJsonContains work_days по датам периода; сверка 25.09, D3). Исключение — «Дафтари
        // қайди в/н» (реестр), он и в legacy по дате создания.
        boolean byDays = !cargo && type != ReportType.REGISTRY_JOURNAL;
        WaybillCalcAssembler.Period period = byDays ? new WaybillCalcAssembler.Period(from, to) : null;

        // Потоком по периоду (WaybillPeriodScan), а не findAll(): агрегируем строки, сущности не копим.
        Map<String, ReportRow> rows = new LinkedHashMap<>();
        // Число разных ТС и подписи групп (legacy «миқдори автомобил», рамз марки, вид маршрута, депо; D13).
        Map<String, Set<String>> vehiclesByKey = new java.util.HashMap<>();
        Set<String> allVehicles = new java.util.HashSet<>();
        Map<String, String[]> groupInfo = new java.util.HashMap<>();
        Map<String, String> routeTypeNames = type.grouping() == ReportGrouping.ROUTE ? routeTypeNames() : Map.of();
        Map<String, Map<String, String>> garageByOrg = new java.util.HashMap<>();
        java.util.function.Consumer<Waybill> handle = wb -> {
            if (!(cargo ? isCargo(wb.getWaybillType()) : isPassenger(wb.getWaybillType()))) {
                return;
            }
            if (!countsInReports(wb)) {
                return;
            }
            if (!f.matches(wb)) {
                return;
            }
            Contribution c = contribution(wb, cargo, period);
            if (c == null) {
                return;   // ни одного рабочего дня в периоде
            }
            String key = groupKey(type.grouping(), wb, c);
            String label = groupLabel(type.grouping(), wb, c, key);
            ReportRow row = rows.computeIfAbsent(key, k -> ReportRow.zero(k, label));
            ReportRow next = row.plus(c.laps, c.distanceKm, c.routeDistanceKm, c.turnover, c.passengers,
                    c.normLiters, c.givenLiters, c.revenue, c.kassa, c.salary,
                    c.workDays, c.workHours, c.transportWork, c.trips, c.fuelSplit)
                    .plusExtra(c.plannedLaps, type == ReportType.DRIVER_SALARY ? clientHours(wb, period) : 0d);
            String vehicle = normPlate(wb.getVehicleRegNumber());
            vehiclesByKey.computeIfAbsent(key, k -> new java.util.HashSet<>()).add(vehicle);
            allVehicles.add(vehicle);
            groupInfo.computeIfAbsent(key, k -> groupInfo(type.grouping(), wb, c, routeTypeNames, garageByOrg));
            // Построчные отчёты legacy (сверка 25.09, D5): реквизиты листа; «Сузишвори» — последний лист ТС.
            if (type.grouping() == ReportGrouping.PER_WAYBILL) {
                next = next.withDetail(detail(wb, c));
            } else if (type == ReportType.FUEL_GENERAL) {
                ReportRow.Detail cur = row.detail();
                if (cur == null || cur.createdAt() == null
                        || (wb.getCreatedAt() != null && !wb.getCreatedAt().isBefore(cur.createdAt()))) {
                    next = next.withDetail(detail(wb, c));
                }
            }
            rows.put(key, next);
        };
        scan.forEach(from, to, scope, handle);
        if (byDays) {
            // «Переходящие» листы: созданы раньше, а рабочие дни — в этом периоде.
            java.util.Set<WaybillType> types = java.util.EnumSet.copyOf(WaybillCalcAssembler.DAY_SCOPED_TYPES);
            if (f.forms() != null) {
                types.retainAll(f.forms());
            }
            scan.forEachCarryOver(from, to, scope, types, handle);
        }

        List<ReportRow> ordered = new ArrayList<>();
        for (ReportRow r : rows.values()) {
            String[] gi = groupInfo.getOrDefault(r.key(), new String[2]);
            ordered.add(r.withGroup(vehiclesByKey.getOrDefault(r.key(), Set.of()).size(), gi[0], gi[1]));
        }
        ordered.sort((a, b) -> a.key().compareToIgnoreCase(b.key()));
        ReportRow totals = ordered.stream().reduce(ReportRow.zero("TOTAL", "ИТОГО"), ReportRow::merge)
                .withGroup(allVehicles.size(), null, null);

        // Промежуточные итоги (legacy: «Хатсайр» — по видам маршрутов, «Автомобил» троллейбусов — по депо).
        Map<String, ReportRow> sub = new java.util.TreeMap<>();
        Map<String, Set<String>> subVehicles = new java.util.HashMap<>();
        for (ReportRow r : ordered) {
            if (r.groupType() == null) {
                continue;
            }
            sub.merge(r.groupType(), ReportRow.zero(r.groupType(), r.groupType()).merge(r), ReportRow::merge);
            subVehicles.computeIfAbsent(r.groupType(), k -> new java.util.HashSet<>())
                    .addAll(vehiclesByKey.getOrDefault(r.key(), Set.of()));
        }
        List<ReportRow> subtotals = new ArrayList<>();
        sub.forEach((k, r) -> subtotals.add(r.withGroup(subVehicles.getOrDefault(k, Set.of()).size(), null, null)));

        return new WaybillReport(type, type.label(), from, to, org, ordered, totals, subtotals);
    }

    /** Подпись группы и группа промежуточного итога (сверка 25.09, D13). */
    private String[] groupInfo(ReportGrouping g, Waybill wb, Contribution c, Map<String, String> routeTypeNames,
                               Map<String, Map<String, String>> garageByOrg) {
        return switch (g) {
            case VEHICLE -> {
                String garage = snapshotString(wb.getVehicleSnapshot(), "parkingNumber");
                if (garage == null && masterData != null && wb.getOrganizationRma() != null) {
                    // Снимок архивного листа без гаражного номера — из реестра ТС (один запрос на организацию).
                    garage = garageByOrg.computeIfAbsent(wb.getOrganizationRma(), this::garageNumbers)
                            .get(normPlate(wb.getVehicleRegNumber()));
                }
                // Депо троллейбуса — первая цифра гаражного номера (legacy BusCalc::type1, bill ebus).
                String depot = wb.getWaybillType() == WaybillType.WB_TROLLEYBUS && garage != null
                        && !garage.isBlank() && Character.isDigit(garage.trim().charAt(0))
                        ? "Депо " + garage.trim().charAt(0) : null;
                yield new String[]{garage, depot};
            }
            case DRIVER -> new String[]{snapshotString(wb.getDriverSnapshot(), "tabNumber"), null};
            case BRAND -> {
                String number = null;
                if (masterData != null && c.brand() != null) {
                    number = masterData.findBrandByName(c.brand()).map(b -> b.get("number"))
                            .map(String::valueOf).orElse(null);
                }
                yield new String[]{number, null};
            }
            case ROUTE -> {
                if (masterData == null || wb.getRoute() == null) {
                    yield new String[2];
                }
                var route = masterData.findRoute(wb.getRoute(), wb.getOrganizationRma()).orElse(null);
                if (route == null) {
                    yield new String[2];
                }
                // Строка группы — маршрут из листа (обычно наименование); вторая подпись — № маршрута, как «Рақами хатсайр».
                String name = route.get("number") == null ? null : String.valueOf(route.get("number"));
                Object code = route.get("routeTypeCode");
                String typeName = code == null ? null : routeTypeNames.getOrDefault(String.valueOf(code), String.valueOf(code));
                yield new String[]{name, typeName};
            }
            default -> new String[2];
        };
    }

    /** Госномер → гаражный номер ТС организации (реестр master-data). */
    private Map<String, String> garageNumbers(String organizationRma) {
        Map<String, String> m = new java.util.HashMap<>();
        try {
            for (Map<String, Object> v : masterData.listVehicles(organizationRma)) {
                Object reg = v.get("registrationNumber");
                Object garage = v.get("parkingNumber");
                if (reg != null && garage != null && !String.valueOf(garage).isBlank()) {
                    m.put(normPlate(String.valueOf(reg)), String.valueOf(garage).trim());
                }
            }
        } catch (RuntimeException e) {
            // реестр недоступен — без гаражных номеров
        }
        return m;
    }

    private Map<String, String> routeTypeNames() {
        if (masterData == null) {
            return Map.of();
        }
        Map<String, String> m = new java.util.HashMap<>();
        for (Map<String, Object> rt : masterData.listRouteTypes()) {
            Object code = rt.get("code");
            Object name = rt.get("nameRu");
            if (code != null && name != null) {
                m.put(String.valueOf(code), String.valueOf(name));
            }
        }
        return m;
    }

    /** Время по заказу за лист, ч (legacy «вақти фармоишӣ» — client_time рабочих дней). */
    private double clientHours(Waybill wb, WaybillCalcAssembler.Period period) {
        if (workDays == null || wb.getId() == null) {
            return 0d;
        }
        boolean dayScoped = period != null && WaybillCalcAssembler.DAY_SCOPED_TYPES.contains(wb.getWaybillType());
        double minutes = 0;
        for (var d : workDays.findByWaybillIdOrderByWorkDate(wb.getId())) {
            if (d.getClientTime() == null) {
                continue;
            }
            if (dayScoped && (d.getWorkDate() == null || d.getWorkDate().isBefore(period.from())
                    || d.getWorkDate().isAfter(period.to()))) {
                continue;
            }
            minutes += d.getClientTime().getHour() * 60 + d.getClientTime().getMinute();
        }
        return minutes / 60d;
    }

    private static String normPlate(String s) {
        return s == null ? "—" : s.replace(" ", "").trim().toUpperCase();
    }

    private record Contribution(long laps, double distanceKm, double routeDistanceKm, double turnover,
                                double passengers, double normLiters, double givenLiters,
                                BigDecimal revenue, BigDecimal kassa, BigDecimal salary,
                                String brand, int workDays, double workHours,
                                double transportWork, double trips, ReportRow.FuelSplit fuelSplit,
                                List<FuelConsumption> fuels, double plannedLaps) {
    }

    private static final java.time.format.DateTimeFormatter DT = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final Map<Long, String> FUEL_NAME = Map.of(1L, "Б", 2L, "С", 3L, "Г", 4L, "Г", 5L, "Э");

    /** Реквизиты листа для построчных отчётов (№, ТС, водитель, одометр, выезд/возврат, топливо). */
    private ReportRow.Detail detail(Waybill wb, Contribution c) {
        List<tj.mintrans.epd.waybill.domain.WorkDay> days = workDays == null || wb.getId() == null
                ? List.of() : workDays.findByWaybillIdOrderByWorkDate(wb.getId());
        Integer odoExit = wb.getOdometerExit() != null ? wb.getOdometerExit()
                : (days.isEmpty() ? null : days.getFirst().getOdometerExit());
        Integer odoEntry = wb.getOdometerEntry() != null ? wb.getOdometerEntry()
                : (days.isEmpty() ? null : days.getLast().getOdometerEntry());
        String exitAt = null;
        String entryAt = null;
        if (!days.isEmpty()) {
            var first = days.getFirst();
            var last = days.getLast();
            exitAt = first.getExitTime() == null ? first.getWorkDate().toString()
                    : DT.format(first.getWorkDate().atTime(first.getExitTime()));
            entryAt = last.getEntryTime() == null ? last.getWorkDate().toString()
                    : DT.format(last.getWorkDate().atTime(last.getEntryTime()));
        }
        double given = 0, before = 0, norm = 0, after = 0;
        java.util.Set<String> types = new java.util.LinkedHashSet<>();
        for (FuelConsumption f : c.fuels() == null ? List.<FuelConsumption>of() : c.fuels()) {
            given += f.given();
            before += f.remainBeforeExit();
            norm += f.normLiters();
            after += f.remainEntry();
            types.add(FUEL_NAME.getOrDefault(f.fuelId(), String.valueOf(f.fuelId())));
        }
        return new ReportRow.Detail(wb.getNumber(), wb.getCreatedAt(), wb.getVehicleRegNumber(),
                snapshotString(wb.getVehicleSnapshot(), "parkingNumber"),
                snapshotString(wb.getDriverSnapshot(), "fullName"),
                snapshotString(wb.getDriverSnapshot(), "tabNumber"),
                wb.getRoute(), exitAt, entryAt, odoExit, odoEntry,
                odoExit != null && odoEntry != null ? odoEntry - odoExit : null,
                types.isEmpty() ? null : String.join("/", types),
                round3(given), round3(before), round3(norm), round3(after));
    }

    private static double round3(double v) {
        return Math.round(v * 1000d) / 1000d;
    }

    /**
     * Норма и выдано по видам топлива Б/С/Г (legacy «меъёр / асл Б/С/Г»; сверка 25.09, D6): бензин — вид 1,
     * солярка — 2, газ — 3 и 4. «Выдано» — то же, что в общей колонке (с надбавкой ниже 0 °C).
     */
    static ReportRow.FuelSplit fuelSplit(List<FuelConsumption> fuels) {
        double nb = 0, ns = 0, ng = 0, gb = 0, gs = 0, gg = 0;
        for (FuelConsumption f : fuels) {
            if (f.fuelId() == 1) { nb += f.normLiters(); gb += f.given(); }
            else if (f.fuelId() == 2) { ns += f.normLiters(); gs += f.given(); }
            else if (f.fuelId() == 3 || f.fuelId() == 4) { ng += f.normLiters(); gg += f.given(); }
        }
        return new ReportRow.FuelSplit(nb, ns, ng, gb, gs, gg);
    }

    private Contribution contribution(Waybill wb, boolean cargo, WaybillCalcAssembler.Period period) {
        WaybillCalcAssembler.View view = period == null
                ? assembler.calculate(wb, WaybillCalcAssembler.Supplement.empty())
                : assembler.calculate(wb, WaybillCalcAssembler.Supplement.empty(), period);
        if (view.outOfPeriod()) {
            return null;
        }
        String brand = brandOf(wb);
        double hours = view.workMinutes() / 60d;
        if (cargo && view.cargo() != null) {
            // Грузовые колонки legacy cargo2b/type_1: рӯзи корӣ, соат, гардиши бор (P), рейсҳо (Z).
            CargoCalcResult r = view.cargo();
            double given = r.fuels().stream().mapToDouble(FuelConsumption::given).sum();
            return new Contribution(0L, r.distanceKm(), 0d, 0d, 0d, r.totalNormLiters(), given,
                    BigDecimal.ZERO, BigDecimal.ZERO, r.salary().salary(), brand,
                    view.workDays(), hours, r.transportWork(), r.trips(), fuelSplit(r.fuels()), r.fuels(), 0d);
        }
        if (view.passenger() != null) {
            PassengerCalcResult r = view.passenger();
            PassengerMetrics m = r.passengerMetrics();
            double given = r.fuels().stream().mapToDouble(FuelConsumption::given).sum();
            return new Contribution(m.laps(), m.totalDistanceKm(), m.routeDistanceKm(),
                    m.passengerTurnover(), m.passengerCount(), r.totalNormLiters(), given,
                    m.earning(), m.kassa(), r.salary().salary(), brand,
                    Math.max(1, m.workDays()), m.workTimeMinutes() / 60d, 0d, 0d, fuelSplit(r.fuels()), r.fuels(),
                    m.plannedLaps());
        }
        return new Contribution(0L, r0(wb), 0d, 0d, 0d, 0d, 0d,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, brand, 1, 0d, 0d, 0d, ReportRow.FuelSplit.ZERO, List.of(), 0d);
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
