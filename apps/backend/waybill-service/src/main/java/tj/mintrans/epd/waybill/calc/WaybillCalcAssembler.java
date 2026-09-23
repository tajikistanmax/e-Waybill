package tj.mintrans.epd.waybill.calc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tj.mintrans.epd.waybill.calc.model.CalcFuelLine;
import tj.mintrans.epd.waybill.calc.model.CargoCalcInput;
import tj.mintrans.epd.waybill.calc.model.CargoCalcResult;
import tj.mintrans.epd.waybill.calc.model.FuelConsumption;
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

    /**
     * B10 — посуточный расчёт многодневных пассажирских 1-А/3-С (MIGRATION.md 5.4): показатели перевозки по
     * дням ({@link MultiDayPassengerCalc}) и посуточный расход топлива по строкам топлива, привязанным к рабочим
     * дням ({@link DailyFuelCalc}). Включён по решению владельца 22.09 (`epd.calc.multiday-passenger`,
     * {@code MULTIDAY_PASSENGER_ENABLED}); {@code false} — прежнее однодневное приближение.
     */
    private final boolean multidayEnabled;

    private final WaybillCalcEngine engine;
    private final MasterDataClient masterData;
    private final WorkDayRepository workDays;
    private final FuelRecordRepository fuelRecords;

    /** Конструктор для тестов/ручной сборки: посуточный расчёт включён (как в проде по умолчанию). */
    public WaybillCalcAssembler(WaybillCalcEngine engine, MasterDataClient masterData,
                                WorkDayRepository workDays, FuelRecordRepository fuelRecords) {
        this(engine, masterData, workDays, fuelRecords, true);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public WaybillCalcAssembler(WaybillCalcEngine engine, MasterDataClient masterData,
                                WorkDayRepository workDays, FuelRecordRepository fuelRecords,
                                @org.springframework.beans.factory.annotation.Value("${epd.calc.multiday-passenger:true}") boolean multidayEnabled) {
        this.engine = engine;
        this.masterData = masterData;
        this.workDays = workDays;
        this.fuelRecords = fuelRecords;
        this.multidayEnabled = multidayEnabled;
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

    /**
     * Результат: одна из веток заполнена. {@code dailyFuel} — посуточный расход топлива многодневного
     * пассажирского ПЛ (B10, MIGRATION.md 5.4), пусто для однодневных/грузовых.
     */
    public record View(String kind, PassengerCalcResult passenger, CargoCalcResult cargo, List<String> notes,
                       List<DailyFuelCalc.DayResult> dailyFuel) {
        public View(String kind, PassengerCalcResult passenger, CargoCalcResult cargo, List<String> notes) {
            this(kind, passenger, cargo, notes, List.of());
        }
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

        List<FuelRecord> records = fuelRecords.findByWaybillIdOrderByCreatedAt(wb.getId());
        List<CalcFuelLine> fuels = toCalcFuelLines(records);
        LocalDate calcDate = s.calcDate() != null ? s.calcDate() : calcDate(wb);

        Map<String, Object> route = masterData.findRoute(wb.getRoute(), wb.getOrganizationRma()).orElse(null);
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
        if (capacity == null || capacity <= 0) {
            // Снимок архивного (перенесённого из legacy) листа не несёт вместимость ТС. Legacy считает
            // пассажирооборот по вместимости МАРКИ (BusBaseCalc: $row->parking->brand->capacity) — берём
            // её из справочника марок (кэшированный список master-data), иначе пассажирооборот = 0.
            Integer brandCapacity = brandCapacity(brandName);
            if (brandCapacity != null) {
                capacity = brandCapacity;
                notes.add("Вместимость взята из справочника марок: " + brandCapacity);
            }
        }
        PassengerCalcInput passengerInput = buildPassenger(wb, s, brandName, year, capacity,
                exitOdo, entryOdo, workMinutes, laps, revenue, fuels, calcDate, route);
        PassengerCalcResult r = engine.passenger(passengerInput);

        // Показатели «свободного» такси (METER, type_service=1) и почасовой аренды (HOURLY=3)
        // маршрутный движок НЕ считает (нет маршрута → нули). Считаем их спец-формулой оригинала
        // (Calc.php::taxi_type/hourly_type) через уже протестированный MultiDayPassengerCalc — для
        // одно- и многодневных ПЛ (однодневный: один день синтезируется из шапки ПЛ). Маршрутное
        // такси (ROUTE=2), автобус/микроавтобус и ТОПЛИВО не затрагиваются, поэтому придержанная B10
        // (посуточные показатели микроавтобуса/маршрута) остаётся в силе (MULTIDAY_PASSENGER_ENABLED=false).
        if (type == WaybillType.WB_TAXI || type == WaybillType.WB_CAR) {
            Short svc = taxiServiceType(wb);
            if (svc != null && (svc == 1 || svc == 3)) {   // METER | HOURLY (ROUTE считает движок)
                List<PassengerDay> taxiDays = days.isEmpty()
                        ? List.of(new PassengerDay(calcDate, null, null, null,
                                exitOdo, entryOdo, null, null,
                                workMinutes > 0 ? workMinutes : null, null, null, null))
                        : passengerDays(days);
                PassengerMetrics taxiMetrics = MultiDayPassengerCalc.forTaxi(
                        svc, null, capacity, capacity, taxiDays, null, null, revenue);
                r = new PassengerCalcResult(r.distanceKm(), r.workTimeMinutes(), r.workHours(),
                        r.coefficients(), r.fuels(), r.totalNormLiters(), r.salary(), taxiMetrics, r.tariff());
                notes.add("Показатели рассчитаны спец-формулой такси (тип обслуживания "
                        + (svc == 1 ? "«свободное»" : "почасовая аренда") + ")");
            }
        }
        // Гэп B10: у МНОГОДНЕВНОГО пассажирского листа форм 1-А (микроавтобус) и 3-С
        // (легковой/такси) показатели перевозки считаются ПОСУТОЧНО (MultiDayPassengerCalc),
        // а не однодневным приближением движка. Однодневный лист (≤ 1 рабочего дня) и прочие
        // формы (автобус/троллейбус/междугородний) идут прежним путём — показатели движка не
        // трогаем (массовый случай остаётся байт-в-байт прежним). Замещаются ТОЛЬКО показатели
        // перевозки; топливо/зарплата/коэффициенты/тариф остаются из движка.
        if (multidayEnabled && days.size() > 1) {
            PassengerMetrics multiDay = multiDayPassengerMetrics(wb, type, days, capacity, revenue, route);
            if (multiDay != null) {
                r = new PassengerCalcResult(r.distanceKm(), r.workTimeMinutes(), r.workHours(),
                        r.coefficients(), r.fuels(), r.totalNormLiters(), r.salary(), multiDay, r.tariff());
                notes.add("Показатели перевозки рассчитаны посуточно: форма " + type.legacyForm()
                        + ", рабочих дней " + days.size());
            }
        }
        // B10, топливо: у 1-А/3-С со строками топлива, привязанными к рабочим дням, расход считается
        // посуточно (DailyFuelCalc = MBusTrait::calcFuel), свод по видам топлива замещает результат движка.
        List<DailyFuelCalc.DayResult> dailyFuel = List.of();
        if (multidayEnabled && !days.isEmpty()
                && (type == WaybillType.WB_MINIBUS || type == WaybillType.WB_CAR || type == WaybillType.WB_TAXI)) {
            dailyFuel = dailyFuel(wb, days, records, passengerInput, route == null);
            if (!dailyFuel.isEmpty()) {
                List<FuelConsumption> agg = DailyFuelCalc.aggregate(dailyFuel);
                r = new PassengerCalcResult(r.distanceKm(), r.workTimeMinutes(), r.workHours(),
                        r.coefficients(), agg, DailyFuelCalc.totalNorm(agg), r.salary(), r.passengerMetrics(), r.tariff());
                notes.add("Топливо рассчитано посуточно по строкам рабочих дней: дней " + dailyFuel.size());
            }
        }
        return new View("PASSENGER", r, null, notes, dailyFuel);
    }

    /**
     * Посуточный расход топлива (B10): строки топлива с {@code workDayId} группируются по рабочим дням; дни без
     * строк дают пробег без расхода. Нет ни одной строки, привязанной к дню, — пусто (остаётся расчёт движка
     * по листу целиком). Таблица нормативов Душанбе — по региону организации (legacy {@code company.region_id == 1}).
     * Довыдача и надбавка ниже 0 °C входят в выданное (как в однодневном {@code fuel_calc}).
     */
    private List<DailyFuelCalc.DayResult> dailyFuel(Waybill wb, List<WorkDay> days, List<FuelRecord> records,
                                                    PassengerCalcInput input, boolean routeAbsent) {
        Map<java.util.UUID, List<FuelRecord>> byDay = new java.util.LinkedHashMap<>();
        for (FuelRecord f : records) {
            if (f.getWorkDayId() != null) {
                byDay.computeIfAbsent(f.getWorkDayId(), k -> new ArrayList<>()).add(f);
            }
        }
        if (byDay.isEmpty()) {
            return List.of();
        }
        List<WaybillCalcEngine.DailySpec> specs = new ArrayList<>();
        for (WorkDay d : days) {
            long dist = d.getOdometerExit() != null && d.getOdometerEntry() != null
                    ? Math.max(0, d.getOdometerEntry() - d.getOdometerExit()) : 0;
            List<DailyFuelCalc.DayLine> lines = new ArrayList<>();
            for (FuelRecord f : byDay.getOrDefault(d.getId(), List.of())) {
                lines.add(new DailyFuelCalc.DayLine(f.getFuelType(),
                        dbl(f.getFuelGiven()) + dbl(f.getCoefBelow0()), dbl(f.getAdditionalGiven()),
                        f.getRemainBeforeExit() == null ? null : f.getRemainBeforeExit().doubleValue()));
            }
            specs.add(new WaybillCalcEngine.DailySpec(d.getWorkDate(), dist,
                    d.getOdometerExit() == null ? null : d.getOdometerExit().longValue(), lines));
        }
        Long orgRegion = longOf(get(organization(wb), "regionId"));
        return engine.passengerDaily(input, orgRegion != null && orgRegion == 1, routeAbsent, specs);
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
        Long orgRegion = longOf(get(organization(wb), "regionId"));
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
        if (ref == null) {
            // Без маршрута посуточный движок даёт нули (нет длины/вместимости маршрута) — оставляем
            // показатели однодневного движка (пробег/круги/время по дням он уже суммирует).
            return null;
        }
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

    /**
     * Вид услуги 3-С → код {@code type_service} (1/2/3); {@code null} — не задан.
     * Основной источник — {@code typeData.serviceKind} (TAXI/ROUTE/HOURLY, ПЛ из живого потока);
     * фолбэк — числовой {@code typeData.typeService} ("1"/"2"/"3", мигрированные ПЛ из legacy).
     */
    private static Short taxiServiceType(Waybill wb) {
        Map<String, Object> td = wb.getTypeData();
        if (td == null) {
            return null;
        }
        String kind = str0(td.get("serviceKind"));
        if (kind != null) {
            if ("TAXI".equalsIgnoreCase(kind.trim())) {
                return (short) 1;   // METER — «свободное» такси, пробег по счётчику
            }
            if ("ROUTE".equalsIgnoreCase(kind.trim())) {
                return (short) 2;   // маршрутное такси
            }
            if ("HOURLY".equalsIgnoreCase(kind.trim())) {
                return (short) 3;   // почасовая аренда
            }
        }
        String ts = str0(td.get("typeService"));   // мигрированные ПЛ: legacy type_service 1/2/3
        if (ts != null && ts.trim().matches("[123]")) {
            return Short.valueOf(ts.trim());
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

        // Направление (Самт) 2-Б — коэффициенты зимы/гор/города берутся из справочника Direction по
        // typeData.directionId (legacy CargoFuelBase::getCoef: $waybill2b->direction->*_coef_id), если
        // Supplement их не переопределяет. Раньше без Supplement K содержал только износ.
        Long dirWinter = s.directionWinterCoefId();
        Long dirMountain = s.directionMountainCoefId();
        Long dirCity = s.directionInCityCoefId();
        if (dirWinter == null && dirMountain == null && dirCity == null) {
            Long directionId = tdLong(wb.getTypeData(), "directionId");
            if (directionId != null) {
                Map<String, Object> direction = masterData.findDirection(directionId).orElse(null);
                if (direction != null) {
                    dirWinter = longOf(direction.get("winterCoefId"));
                    dirMountain = longOf(direction.get("mountainCoefId"));
                    dirCity = longOf(direction.get("inCityCoefId"));
                } else {
                    log.warn("Направление id={} ПЛ {} не найдено в справочнике — коэффициенты направления не применены",
                            directionId, wb.getId());
                }
            }
        }
        // Прицеп: масса/грузоподъёмность — из снимка ТС (legacy parkings.weight_ydak / carrying_ydak /
        // weight_ydak_2), если Supplement не задаёт. Раньше без Supplement надбавка за прицеп была 0.
        Map<String, Object> veh = wb.getVehicleSnapshot();
        double trailerWeight = s.trailerWeight() != null ? s.trailerWeight() : nzd(dblOf(veh == null ? null : veh.get("trailer1Weight")));
        double trailerCarrying = s.trailerCarrying() != null ? s.trailerCarrying() : nzd(dblOf(veh == null ? null : veh.get("trailer1Carrying")));
        double trailerWeight2 = s.trailerWeight2() != null ? s.trailerWeight2() : nzd(dblOf(veh == null ? null : veh.get("trailer2Weight")));

        return CargoCalcInput.builder()
                .brandName(brandName)
                .brandNumber(brandCode)
                .vehicleYearManufacture(year)
                .directionWinterCoefId(dirWinter)
                .directionMountainCoefId(dirMountain)
                .directionInCityCoefId(dirCity)
                .applyCoefficient(!intl)
                .odometerExit(exitOdo)
                .odometerEntry(entryOdo)
                .transportWork(s.transportWork() == null ? 0d : s.transportWork())
                .trips(s.trips() == null ? 0d : s.trips())
                .specialWorkHours(s.specialWorkHours() == null ? 0d : s.specialWorkHours())
                .specialDistance(s.specialDistance() == null ? 0d : s.specialDistance())
                .trailerWeight(trailerWeight)
                .trailerCarrying(trailerCarrying)
                .trailerWeight2(trailerWeight2)
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

    /** Вместимость марки из справочника master-data; {@code null} — марка не найдена / справочник недоступен. */
    private Integer brandCapacity(String brandName) {
        if (brandName == null || brandName.isBlank()) {
            return null;
        }
        try {
            Integer c = masterData.findBrandByName(brandName)
                    .map(b -> longOf(b.get("capacity")))
                    .map(Long::intValue)
                    .orElse(null);
            return c != null && c > 0 ? c : null;
        } catch (RuntimeException e) {
            log.warn("Справочник марок недоступен ({}) — вместимость по марке не подставлена", e.toString());
            return null;
        }
    }

    /** Поля организации, которые расчёт берёт из справочника, если архивный снимок их не несёт. */
    private static final List<String> ORG_CALC_FIELDS = List.of("percentIncome", "cat1", "cat2", "cat3", "regionId");

    /** Индекс организаций по РМА, построенный по последнему (кэшированному) списку master-data. */
    private volatile List<Map<String, Object>> orgIndexSource;
    private volatile Map<String, Map<String, Object>> orgIndex = Map.of();

    /**
     * Организация для расчёта. Для живого листа — его снимок (как было). Снимок архивного листа,
     * перенесённого из legacy ({@code migrated=true}), несёт только РМА и название: доля дохода,
     * надбавки за класс и регион дополняются из справочника организаций master-data — так же, как
     * legacy берёт их у текущей компании ({@code $row->company->percent_income}, {@code cat_N},
     * {@code region_id}). Справочник недоступен — остаётся снимок.
     */
    private Map<String, Object> organization(Waybill wb) {
        Map<String, Object> snap = wb.getOrganizationSnapshot();
        if (snap == null || !Boolean.TRUE.equals(snap.get("migrated")) || wb.getOrganizationRma() == null) {
            return snap;
        }
        Map<String, Object> ref;
        try {
            ref = organizationIndex().get(wb.getOrganizationRma());
        } catch (RuntimeException e) {
            log.warn("Справочник организаций недоступен ({}) — архивный лист считается по снимку", e.toString());
            return snap;
        }
        if (ref == null) {
            return snap;
        }
        Map<String, Object> merged = new java.util.HashMap<>(snap);
        for (String f : ORG_CALC_FIELDS) {
            if (merged.get(f) == null && ref.get(f) != null) {
                merged.put(f, ref.get(f));
            }
        }
        return merged;
    }

    private Map<String, Map<String, Object>> organizationIndex() {
        List<Map<String, Object>> list = masterData.listOrganizations();   // кэш 60 с на вызывающего
        if (list != orgIndexSource) {
            Map<String, Map<String, Object>> idx = new java.util.HashMap<>();
            for (Map<String, Object> o : list) {
                if (o.get("rma") != null) {
                    idx.putIfAbsent(o.get("rma").toString(), o);
                }
            }
            orgIndex = idx;
            orgIndexSource = list;
        }
        return orgIndex;
    }

    /** Значение из организации (снимок / справочник для архивного листа), иначе — из дополнения. */
    private Double orgDouble(Waybill wb, String field, Double fallback) {
        Double v = dblOf(get(organization(wb), field));
        return v != null ? v : fallback;
    }

    private Short orgShort(Waybill wb, String field, Short fallback) {
        Long v = longOf(get(organization(wb), field));
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

    /**
     * Записи топлива ПЛ → строки входа расчёта. Перенос 1-в-1 (MIGRATION.md §5.6): надбавка
     * при t° ниже 0 ({@code coef_below_0}, в оригинале прибавляется к выданному — helpers.php
     * {@code fuel_calc}/{@code fuel_calc_day}) и довыдача в пути ({@code additional} legacy =
     * {@code additional_given}; в оригинале входит в «give» и в остаток при возврате) теперь
     * передаются в движок, а не нулями. {@code be_given} в формулах не участвует.
     */
    static List<CalcFuelLine> toCalcFuelLines(List<FuelRecord> records) {
        List<CalcFuelLine> lines = new ArrayList<>();
        if (records == null) {
            return lines;
        }
        for (FuelRecord fr : records) {
            lines.add(new CalcFuelLine(fr.getFuelType(),
                    dbl(fr.getFuelGiven()),
                    dbl(fr.getCoefBelow0()),
                    dbl(fr.getAdditionalGiven()),
                    dbl(fr.getRemainBeforeExit())));
        }
        return lines;
    }

    private static double dbl(BigDecimal v) {
        return v == null ? 0d : v.doubleValue();
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

    private static Long longOf(Object v) {
        if (v == null || v.toString().isBlank()) {
            return null;
        }
        try {
            return v instanceof Number n ? n.longValue() : (long) Double.parseDouble(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double dblOf(Object v) {
        if (v == null || v.toString().isBlank()) {
            return null;
        }
        try {
            return v instanceof Number n ? n.doubleValue() : Double.valueOf(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static double nzd(Double v) {
        return v == null ? 0d : v;
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
