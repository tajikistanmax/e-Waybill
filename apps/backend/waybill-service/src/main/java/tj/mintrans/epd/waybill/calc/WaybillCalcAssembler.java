package tj.mintrans.epd.waybill.calc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tj.mintrans.epd.waybill.calc.model.CalcFuelLine;
import tj.mintrans.epd.waybill.calc.model.CargoCalcInput;
import tj.mintrans.epd.waybill.calc.model.CargoCalcResult;
import tj.mintrans.epd.waybill.calc.model.PassengerCalcInput;
import tj.mintrans.epd.waybill.calc.model.PassengerCalcResult;
import tj.mintrans.epd.waybill.calc.model.PassengerDay;
import tj.mintrans.epd.waybill.calc.model.PassengerMetrics;
import tj.mintrans.epd.waybill.calc.model.RoutePassengerRef;
import tj.mintrans.epd.waybill.client.MasterDataClient;
import tj.mintrans.epd.waybill.domain.FuelRecord;
import tj.mintrans.epd.waybill.domain.Waybill;
import tj.mintrans.epd.waybill.domain.WaybillType;
import tj.mintrans.epd.waybill.domain.WorkDay;
import tj.mintrans.epd.waybill.repository.FuelRecordRepository;
import tj.mintrans.epd.waybill.repository.WorkDayRepository;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Сборка входа расчёта из существующего путевого листа: снимки ТС/организации,
 * рабочие дни ({@link WorkDay}), записи топлива ({@link FuelRecord}), маршрут из
 * master-data. Недостающие в модели e-Waybill величины (кондиционер, транспортная
 * работа P, число ездок Z, доля дохода компании) передаются в {@link Supplement}.
 *
 * <p>Запускает {@link WaybillCalcEngine} — пассажирскую или грузовую ветку по типу ПЛ.</p>
 */
@Service
public class WaybillCalcAssembler {

    private static final Logger log = LoggerFactory.getLogger(WaybillCalcAssembler.class);

    private final WaybillCalcEngine engine;
    private final MasterDataClient masterData;
    private final WorkDayRepository workDays;
    private final FuelRecordRepository fuelRecords;

    public WaybillCalcAssembler(WaybillCalcEngine engine, MasterDataClient masterData,
                                WorkDayRepository workDays, FuelRecordRepository fuelRecords) {
        this.engine = engine;
        this.masterData = masterData;
        this.workDays = workDays;
        this.fuelRecords = fuelRecords;
    }

    /** Дополнение к данным ПЛ — всё необязательно. */
    public record Supplement(
            Integer airConditionerPercent,
            Double conditionerHours,
            Long numberLap,
            Double transportWork,
            Double trips,
            Double specialWorkHours,
            Double specialDistance,
            Long directionWinterCoefId,
            Long directionMountainCoefId,
            Long directionInCityCoefId,
            Double trailerWeight,
            Double trailerCarrying,
            Double trailerWeight2,
            BigDecimal earning,
            Double companyPercentIncome,
            Integer driverDegree,
            Short companyCat1,
            Short companyCat2,
            Short companyCat3,
            Double tariffPricePer1Mkm,
            Double tariffPriceOneTime,
            Boolean speedometerTotalDistance,
            LocalDate calcDate
    ) {
        public static Supplement empty() {
            return new Supplement(null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null);
        }
    }

    /** Результат: одна из веток заполнена. */
    public record View(String kind, PassengerCalcResult passenger, CargoCalcResult cargo, List<String> notes) {
    }

    public View calculate(Waybill wb, Supplement sup) {
        Supplement s = mergeTypeData(wb, sup == null ? Supplement.empty() : sup);
        List<String> notes = new ArrayList<>();

        Map<String, Object> veh = wb.getVehicleSnapshot();
        String brandName = str(veh, "brand");
        LocalDate year = firstOfYear(veh == null ? null : veh.get("yearManufacture"));
        Integer capacity = intOf(veh == null ? null : veh.get("capacity"));

        List<WorkDay> days = workDays.findByWaybillIdOrderByWorkDate(wb.getId());
        long exitOdo = wb.getOdometerExit() != null ? wb.getOdometerExit()
                : (!days.isEmpty() && days.getFirst().getOdometerExit() != null ? days.getFirst().getOdometerExit() : 0L);
        long distance = tripDistance(wb, days, notes);
        long entryOdo = exitOdo + distance;

        int workMinutes = totalWorkMinutes(days);
        long laps = days.stream().filter(d -> d.getLaps() != null).mapToLong(WorkDay::getLaps).sum();
        if (laps == 0 && s.numberLap() != null) {
            laps = s.numberLap();
        }
        BigDecimal revenue = days.stream().map(WorkDay::getRevenue).filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (revenue.signum() == 0 && s.earning() != null) {
            revenue = s.earning();
        }

        List<CalcFuelLine> fuels = fuelLines(wb.getId());
        LocalDate calcDate = s.calcDate() != null ? s.calcDate() : calcDate(wb);

        Map<String, Object> route = masterData.findRoute(wb.getRoute()).orElse(null);
        if (route == null && wb.getRoute() != null && !wb.getRoute().isBlank()) {
            notes.add("Маршрут «" + wb.getRoute() + "» не найден в справочнике — коэффициенты и путевые показатели не применены");
        }

        WaybillType type = wb.getWaybillType();
        boolean cargo = type == WaybillType.WB_TRUCK || type == WaybillType.WB_TRUCK_INTL
                || type == WaybillType.WB_SPECIAL || type == WaybillType.WB_DANGEROUS;

        if (cargo) {
            CargoCalcResult r = engine.cargo(buildCargo(wb, s, brandName, year, exitOdo, entryOdo, revenue, fuels, calcDate));
            return new View("CARGO", null, r, notes);
        }
        PassengerCalcResult r = engine.passenger(buildPassenger(wb, s, brandName, year, capacity,
                exitOdo, entryOdo, workMinutes, laps, revenue, fuels, calcDate, route));
        // Гэп B10: у МНОГОДНЕВНОГО пассажирского листа форм 1-А (микроавтобус) и 3-С
        // (легковой/такси) показатели перевозки считаются ПОСУТОЧНО (MultiDayPassengerCalc),
        // а не однодневным приближением движка. Однодневный лист (≤ 1 рабочего дня) и прочие
        // формы (автобус/троллейбус/междугородний) идут прежним путём — показатели движка не
        // трогаем (массовый случай остаётся байт-в-байт прежним). Замещаются ТОЛЬКО показатели
        // перевозки; топливо/зарплата/коэффициенты/тариф остаются из движка.
        if (days.size() > 1) {
            PassengerMetrics multiDay = multiDayPassengerMetrics(wb, type, days, capacity, revenue, route);
            if (multiDay != null) {
                r = new PassengerCalcResult(r.distanceKm(), r.workTimeMinutes(), r.workHours(),
                        r.coefficients(), r.fuels(), r.totalNormLiters(), r.salary(), multiDay, r.tariff());
                notes.add("Показатели перевозки рассчитаны посуточно: форма " + type.legacyForm()
                        + ", рабочих дней " + days.size());
            }
        }
        return new View("PASSENGER", r, null, notes);
    }

    /**
     * Дополняет {@link Supplement} величинами, зафиксированными в {@code typeData} при
     * возврате рейса (транспортная работа P, число ездок Z, часы кондиционера, % кондиционера,
     * число кругов) — тело запроса `/calculation` их переопределяет, но без него отчёты и
     * так получают реальные значения, а не нули.
     */
    private static Supplement mergeTypeData(Waybill wb, Supplement s) {
        Map<String, Object> td = wb.getTypeData();
        if (td == null || td.isEmpty()) {
            return s;
        }
        return new Supplement(
                s.airConditionerPercent() != null ? s.airConditionerPercent() : tdInt(td, "airConditionerPercent"),
                s.conditionerHours() != null ? s.conditionerHours() : tdDouble(td, "conditionerHours"),
                s.numberLap() != null ? s.numberLap() : tdLong(td, "numberLap"),
                s.transportWork() != null ? s.transportWork() : tdDouble(td, "transportWork"),
                s.trips() != null ? s.trips() : tdDouble(td, "trips"),
                s.specialWorkHours() != null ? s.specialWorkHours() : tdDouble(td, "specialWorkHours"),
                s.specialDistance() != null ? s.specialDistance() : tdDouble(td, "specialDistance"),
                s.directionWinterCoefId(), s.directionMountainCoefId(), s.directionInCityCoefId(),
                s.trailerWeight(), s.trailerCarrying(), s.trailerWeight2(),
                s.earning(), s.companyPercentIncome(), s.driverDegree(),
                s.companyCat1(), s.companyCat2(), s.companyCat3(),
                s.tariffPricePer1Mkm(), s.tariffPriceOneTime(), s.speedometerTotalDistance(), s.calcDate());
    }

    private static Double tdDouble(Map<String, Object> td, String key) {
        Object v = td.get(key);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return v == null || v.toString().isBlank() ? null : Double.valueOf(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long tdLong(Map<String, Object> td, String key) {
        Double d = tdDouble(td, key);
        return d == null ? null : d.longValue();
    }

    private static Integer tdInt(Map<String, Object> td, String key) {
        Double d = tdDouble(td, key);
        return d == null ? null : d.intValue();
    }

    // ------------------------------------------------------------------- пассажир

    private PassengerCalcInput buildPassenger(Waybill wb, Supplement s, String brandName, LocalDate year,
                                              Integer capacity, long exitOdo, long entryOdo, int workMinutes,
                                              long laps, BigDecimal revenue, List<CalcFuelLine> fuels,
                                              LocalDate calcDate, Map<String, Object> route) {
        Map<String, Object> org = wb.getOrganizationSnapshot();
        Integer orgRegion = intOf(org == null ? null : org.get("regionId"));
        boolean speedometer = s.speedometerTotalDistance() != null ? s.speedometerTotalDistance()
                : (wb.getWaybillType() == WaybillType.WB_BUS && orgRegion != null && orgRegion == 1);

        Map<String, Object> tariff = firstRouteTariff(route);

        return PassengerCalcInput.builder()
                .brandName(brandName)
                .vehicleYearManufacture(year)
                .airConditionerPercent(s.airConditionerPercent() == null ? 0 : s.airConditionerPercent())
                .capacity(capacity)
                .routeWinterCoefId(lng(route, "winterCoefId"))
                .routeMountainCoefValue(lng(route, "mountainCoefValue"))
                .routeInCityCoefValue(lng(route, "inCityCoefValue"))
                .routeStationCoef(intOf(get(route, "stationCoef")))
                .routeRoadQuality(intOf(get(route, "roadQuality")))
                .routeExcludingCoef(bool(route, "excludingCoef"))
                .routeAdditionalFuel100(dbl(route, "additionalFuel100"))
                .routeAdditionalFuel(dbl(route, "additionalFuel"))
                .routeCondFuel(dbl(route, "condFuel"))
                .routeHeatingFuel(dbl(route, "heatingFuel"))
                .routeDistanceA(dbl(route, "distanceA"))
                .routeDistanceB(dbl(route, "distanceB"))
                .routeBeginPathA(dbl(route, "beginPathA"))
                .routeBeginPathB(dbl(route, "beginPathB"))
                .routePlannedLap(intOf(get(route, "plannedLap")))
                .routeCoeUseCapacity(dbl(route, "coeUseCapacity"))
                .routeAverageLengthPassSeat(dbl(route, "averageLengthPassSeat"))
                .odometerExit(exitOdo)
                .odometerEntry(entryOdo)
                .workTimeMinutes(workMinutes)
                .conditionerHours(s.conditionerHours() == null ? 0d : s.conditionerHours())
                .numberLap(laps)
                .workDays(Math.max(1, (int) workDays.countByWaybillId(wb.getId())))
                .speedometerTotalDistance(speedometer)
                .calcDate(calcDate)
                .fuels(fuels)
                .earning(revenue)
                // касса = revenue: в оригинале это одно и то же поле (Waybill3c/Waybill1a.kassa,
                // MBusCalc.php: daromad = row->kassa) — портирован в два разных выходных поля
                // (earning питает расчёт зарплаты, kassa — отдельную колонку отчёта), источник один.
                .kassa(revenue)
                .companyPercentIncome(orgDouble(wb, "percentIncome", s.companyPercentIncome()))
                .driverDegree(driverDegree(wb, s.driverDegree()))
                .companyCat1(orgShort(wb, "cat1", s.companyCat1()))
                .companyCat2(orgShort(wb, "cat2", s.companyCat2()))
                .companyCat3(orgShort(wb, "cat3", s.companyCat3()))
                .tariffPricePer1Mkm(s.tariffPricePer1Mkm() != null ? s.tariffPricePer1Mkm() : dbl(tariff, "pricePer1Mkm"))
                .tariffPriceOneTime(s.tariffPriceOneTime() != null ? s.tariffPriceOneTime() : dbl(tariff, "priceOneTime"))
                .build();
    }

    // -------------------------------------------------- посуточные показатели (гэп B10)

    /**
     * Посуточные показатели пассажирской перевозки для МНОГОДНЕВНОГО листа форм 1-А
     * (микроавтобус) и 3-С (легковой/такси) через {@link MultiDayPassengerCalc}.
     *
     * <p>Возвращает {@code null} (→ сохраняются показатели движка), когда форма не относится
     * к посуточным (автобус/троллейбус/междугородний) либо у 3-С не определён вид услуги
     * ({@code typeData.serviceKind}) — консервативно не затираем расчёт движка.</p>
     *
     * <p>Период не сужается ({@code from=to=null}): {@code days} — это ровно рабочие дни
     * данного листа, считаем по всему листу. Посуточная нарезка на отчётные периоды —
     * задача уровня отчётов, а не расчёта одного ПЛ.</p>
     */
    private PassengerMetrics multiDayPassengerMetrics(Waybill wb, WaybillType type, List<WorkDay> days,
                                                      Integer capacity, BigDecimal kassa,
                                                      Map<String, Object> route) {
        RoutePassengerRef ref = routePassengerRef(route);
        List<PassengerDay> passengerDays = passengerDays(days);
        return switch (type) {
            case WB_MINIBUS -> MultiDayPassengerCalc.forMinibus(ref, capacity, passengerDays, null, null, kassa);
            case WB_CAR, WB_TAXI -> {
                Short typeService = taxiServiceType(wb);
                // brandCapacity и vehicleCapacity: в модели одна вместимость — передаём её в обе роли.
                yield typeService == null ? null
                        : MultiDayPassengerCalc.forTaxi(typeService, ref, capacity, capacity,
                                passengerDays, null, null, kassa);
            }
            default -> null;
        };
    }

    /** Путевые поля маршрута для посуточного расчёта; {@code null} → показатели нулевые (как у движка). */
    private static RoutePassengerRef routePassengerRef(Map<String, Object> route) {
        if (route == null) {
            return null;
        }
        return new RoutePassengerRef(dbl(route, "distanceA"), dbl(route, "distanceB"),
                dbl(route, "coeUseCapacity"), dbl(route, "averageLengthPassSeat"),
                intOf(get(route, "plannedLap")), dbl(route, "beginPathA"), dbl(route, "beginPathB"));
    }

    /**
     * Рабочие дни листа → входы посуточного движка. Селекторы нулевого пробега
     * ({@code beginPathA}/{@code beginPathB}) в модели {@link WorkDay} e-Waybill пока не
     * хранятся — передаются {@code null} (нулевой пробег по дню = 0); {@code workTimeInMinutes}
     * тоже отсутствует — движок восполнит время по выезду/возврату.
     */
    private static List<PassengerDay> passengerDays(List<WorkDay> days) {
        List<PassengerDay> result = new ArrayList<>(days.size());
        for (WorkDay d : days) {
            result.add(new PassengerDay(
                    d.getWorkDate(),
                    d.getLaps(),
                    null,
                    null,
                    d.getOdometerExit() == null ? null : d.getOdometerExit().longValue(),
                    d.getOdometerEntry() == null ? null : d.getOdometerEntry().longValue(),
                    d.getExitTime(),
                    d.getEntryTime(),
                    null,
                    d.getConditionerHours(),
                    d.getClientId(),
                    d.getClientTime()));
        }
        return result;
    }

    /** Вид услуги 3-С ({@code typeData.serviceKind}) → код {@code type_service} (1/2/3); {@code null} — не задан. */
    private static Short taxiServiceType(Waybill wb) {
        Map<String, Object> td = wb.getTypeData();
        String kind = td == null ? null : str0(td.get("serviceKind"));
        if (kind == null) {
            return null;
        }
        if ("TAXI".equalsIgnoreCase(kind.trim())) {
            return (short) 1;   // METER — «свободное» такси, пробег по счётчику
        }
        if ("ROUTE".equalsIgnoreCase(kind.trim())) {
            return (short) 2;   // маршрутное такси
        }
        if ("HOURLY".equalsIgnoreCase(kind.trim())) {
            return (short) 3;   // почасовая аренда
        }
        return null;
    }

    // ------------------------------------------------------------------- груз

    private CargoCalcInput buildCargo(Waybill wb, Supplement s, String brandName, LocalDate year,
                                      long exitOdo, long entryOdo, BigDecimal revenue,
                                      List<CalcFuelLine> fuels, LocalDate calcDate) {
        String brandCode = masterData.findBrandByName(brandName)
                .map(m -> str0(m.get("number"))).orElse(null);
        boolean intl = wb.getWaybillType() == WaybillType.WB_TRUCK_INTL;

        return CargoCalcInput.builder()
                .brandName(brandName)
                .brandNumber(brandCode)
                .vehicleYearManufacture(year)
                .directionWinterCoefId(s.directionWinterCoefId())
                .directionMountainCoefId(s.directionMountainCoefId())
                .directionInCityCoefId(s.directionInCityCoefId())
                .applyCoefficient(!intl)
                .odometerExit(exitOdo)
                .odometerEntry(entryOdo)
                .transportWork(s.transportWork() == null ? 0d : s.transportWork())
                .trips(s.trips() == null ? 0d : s.trips())
                .specialWorkHours(s.specialWorkHours() == null ? 0d : s.specialWorkHours())
                .specialDistance(s.specialDistance() == null ? 0d : s.specialDistance())
                .trailerWeight(s.trailerWeight() == null ? 0d : s.trailerWeight())
                .trailerCarrying(s.trailerCarrying() == null ? 0d : s.trailerCarrying())
                .trailerWeight2(s.trailerWeight2() == null ? 0d : s.trailerWeight2())
                .calcDate(calcDate)
                .fuels(fuels)
                .earning(revenue)
                .companyPercentIncome(orgDouble(wb, "percentIncome", s.companyPercentIncome()))
                .driverDegree(driverDegree(wb, s.driverDegree()))
                .companyCat1(orgShort(wb, "cat1", s.companyCat1()))
                .companyCat2(orgShort(wb, "cat2", s.companyCat2()))
                .companyCat3(orgShort(wb, "cat3", s.companyCat3()))
                .build();
    }

    // ------------------------------------------------------------------- helpers

    /** Значение из снимка организации, иначе — из дополнения. */
    private static Double orgDouble(Waybill wb, String field, Double fallback) {
        Double v = dbl(wb.getOrganizationSnapshot(), field);
        return v != null ? v : fallback;
    }

    private static Short orgShort(Waybill wb, String field, Short fallback) {
        Integer v = intOf(get(wb.getOrganizationSnapshot(), field));
        if (v != null) {
            return v.shortValue();
        }
        return fallback;
    }

    /** Класс (разряд) водителя: из снимка водителя ({@code degree}), иначе — из дополнения. */
    private static Integer driverDegree(Waybill wb, Integer fallback) {
        Integer v = intOf(get(wb.getDriverSnapshot(), "degree"));
        return v != null && v > 0 ? v : fallback;
    }

    private long tripDistance(Waybill wb, List<WorkDay> days, List<String> notes) {
        if (wb.getOdometerExit() != null && wb.getOdometerEntry() != null) {
            return Math.max(0L, wb.getOdometerEntry() - wb.getOdometerExit());
        }
        long sum = 0L;
        for (WorkDay d : days) {
            if (d.getOdometerExit() != null && d.getOdometerEntry() != null) {
                sum += Math.max(0, d.getOdometerEntry() - d.getOdometerExit());
            }
        }
        if (sum == 0 && !days.isEmpty()) {
            notes.add("Показания одометра не заполнены ни в ПЛ, ни в рабочих днях — пробег принят 0");
        }
        return sum;
    }

    private static int totalWorkMinutes(List<WorkDay> days) {
        int total = 0;
        for (WorkDay d : days) {
            if (d.getExitTime() != null && d.getEntryTime() != null) {
                total += (int) Math.abs(Duration.between(d.getExitTime(), d.getEntryTime()).toMinutes());
            }
        }
        return total;
    }

    private List<CalcFuelLine> fuelLines(java.util.UUID waybillId) {
        List<CalcFuelLine> lines = new ArrayList<>();
        for (FuelRecord fr : fuelRecords.findByWaybillIdOrderByCreatedAt(waybillId)) {
            lines.add(new CalcFuelLine(fr.getFuelType(),
                    fr.getFuelGiven() == null ? 0d : fr.getFuelGiven().doubleValue(),
                    0d, 0d,
                    fr.getRemainBeforeExit() == null ? 0d : fr.getRemainBeforeExit().doubleValue()));
        }
        return lines;
    }

    private static LocalDate calcDate(Waybill wb) {
        OffsetDateTime d = wb.getValidFrom() != null ? wb.getValidFrom() : wb.getCreatedAt();
        return d != null ? d.toLocalDate() : LocalDate.now();
    }

    private Map<String, Object> firstRouteTariff(Map<String, Object> route) {
        if (route == null || route.get("id") == null) {
            return null;
        }
        List<Map<String, Object>> tariffs = masterData.listRouteTariffs(str0(route.get("id")));
        return tariffs.isEmpty() ? null : tariffs.getFirst();
    }

    private static Object get(Map<String, Object> m, String k) {
        return m == null ? null : m.get(k);
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = get(m, k);
        return v == null ? "" : v.toString();
    }

    private static String str0(Object v) {
        return v == null ? null : v.toString();
    }

    private static Long lng(Map<String, Object> m, String k) {
        Object v = get(m, k);
        if (v == null) {
            return null;
        }
        return v instanceof Number n ? n.longValue() : Long.valueOf(v.toString());
    }

    private static Integer intOf(Object v) {
        if (v == null) {
            return null;
        }
        return v instanceof Number n ? n.intValue() : Integer.valueOf(v.toString());
    }

    private static Double dbl(Map<String, Object> m, String k) {
        Object v = get(m, k);
        if (v == null) {
            return null;
        }
        return v instanceof Number n ? n.doubleValue() : Double.valueOf(v.toString());
    }

    private static boolean bool(Map<String, Object> m, String k) {
        Object v = get(m, k);
        return v instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(v));
    }

    private static LocalDate firstOfYear(Object yearManufacture) {
        if (yearManufacture == null) {
            return null;
        }
        String s = yearManufacture.toString().trim();
        try {
            if (s.length() >= 10) {
                return LocalDate.parse(s.substring(0, 10));
            }
            if (s.matches("\\d{4}")) {
                return LocalDate.of(Integer.parseInt(s), 1, 1);
            }
        } catch (RuntimeException e) {
            log.warn("Год выпуска ТС '{}' не разобран", s);
        }
        return null;
    }
}
