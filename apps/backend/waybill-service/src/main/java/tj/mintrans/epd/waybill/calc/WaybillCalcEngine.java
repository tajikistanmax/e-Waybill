package tj.mintrans.epd.waybill.calc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tj.mintrans.epd.waybill.calc.model.BrandNorms;
import tj.mintrans.epd.waybill.calc.model.CalcFuelLine;
import tj.mintrans.epd.waybill.calc.model.CargoCalcInput;
import tj.mintrans.epd.waybill.calc.model.CargoCalcResult;
import tj.mintrans.epd.waybill.calc.model.CargoFuelRequest;
import tj.mintrans.epd.waybill.calc.model.CoefficientBreakdown;
import tj.mintrans.epd.waybill.calc.model.DirectionCoefRef;
import tj.mintrans.epd.waybill.calc.model.DriverSalary;
import tj.mintrans.epd.waybill.calc.model.FuelConsumption;
import tj.mintrans.epd.waybill.calc.model.FuelNorm;
import tj.mintrans.epd.waybill.calc.model.FuelNormRequest;
import tj.mintrans.epd.waybill.calc.model.FuelRow;
import tj.mintrans.epd.waybill.calc.model.PassengerCalcInput;
import tj.mintrans.epd.waybill.calc.model.PassengerCalcResult;
import tj.mintrans.epd.waybill.calc.model.PassengerMetrics;
import tj.mintrans.epd.waybill.calc.model.RouteCoefRef;
import tj.mintrans.epd.waybill.calc.model.TariffAmount;

import java.util.Optional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Оркестратор расчёта пассажирского путевого листа — перенос
 * {@code WaybillCalculationService.calculate} + {@code PassengerMetricsService.forBus}
 * из ИС «Роҳхат» (docs/spec/07-calculations.md §2.1, §1.3, §1.5, §2.6).
 *
 * <p>Собирает воедино: сводный коэффициент → нормативный расход по видам топлива →
 * остаток топлива → заработок водителя → пассажирские показатели → тариф маршрута.
 * Не обращается к сущностям: вход — самодостаточный {@link PassengerCalcInput}
 * (оркестратор в контроллере собирает его из снимков ПЛ и справочников).</p>
 */
@Service
public class WaybillCalcEngine {

    private static final Logger log = LoggerFactory.getLogger(WaybillCalcEngine.class);

    private final CoefficientCalculator coefficients;
    private final FuelNormCalculator fuel;
    private final BrandNormsProvider brandNorms;
    private final InteriorHeatingMode heatingMode;

    /** Конструктор для тестов/ручной сборки: отопление салона выключено (паритет с legacy). */
    public WaybillCalcEngine(CoefficientCalculator coefficients, FuelNormCalculator fuel,
                             BrandNormsProvider brandNorms) {
        this(coefficients, fuel, brandNorms, InteriorHeatingMode.OFF);
    }

    @Autowired
    public WaybillCalcEngine(CoefficientCalculator coefficients, FuelNormCalculator fuel,
                             BrandNormsProvider brandNorms,
                             @Value("${epd.calc.interior-heating:OFF}") InteriorHeatingMode heatingMode) {
        this.coefficients = coefficients;
        this.fuel = fuel;
        this.brandNorms = brandNorms;
        this.heatingMode = heatingMode == null ? InteriorHeatingMode.OFF : heatingMode;
    }

    /**
     * Надбавка на отопление салона применяется по {@link InteriorHeatingMode}: OFF — никогда
     * (как в legacy, где {@code warm_salon = 0}); WINTER — только при действующем зимнем
     * коэффициенте маршрута (замысел закомментированного кода {@code helpers.php:348});
     * ALWAYS — круглый год. Ветка Душанбе ({@code excluding_coef}) берёт отопление из
     * {@code routes.heating_fuel} и сюда не попадает.
     */
    private boolean interiorHeatingApplies(CoefficientBreakdown coef) {
        return switch (heatingMode) {
            case OFF -> false;
            case WINTER -> coef != null && coef.winterCoef() > 0;
            case ALWAYS -> true;
        };
    }

    /**
     * Расчёт пассажирского путевого листа.
     *
     * @param in вход расчёта
     * @return сводный результат
     */
    public PassengerCalcResult passenger(PassengerCalcInput in) {
        if (in == null) {
            throw new IllegalArgumentException("Вход расчёта не задан");
        }

        int distanceKm = WaybillMath.distanceNonNegative(in.odometerEntry(), in.odometerExit());
        int workMinutes = Math.max(in.workTimeMinutes(), 0);
        double workHours = CalcUtils.minutesToHours(workMinutes);
        double conditionerHours = in.conditionerHours();
        boolean excludingCoef = in.routeExcludingCoef();
        LocalDate calcDate = in.calcDate() != null ? in.calcDate() : LocalDate.now();

        RouteCoefRef routeCoef = new RouteCoefRef(
                in.routeWinterCoefId(), in.routeMountainCoefValue(), in.routeInCityCoefValue(),
                in.routeStationCoef(), in.routeRoadQuality());

        CoefficientBreakdown coef = excludingCoef
                ? CoefficientBreakdown.neutral()
                : coefficients.passengerCoefficient(routeCoef, in.vehicleYearManufacture(),
                        in.odometerExit(), calcDate);

        BrandNorms brand = brandNorms.forName(in.brandName());
        List<FuelNorm> norms = fuel.resolveNorms(brand, excludingCoef);
        List<FuelRow> rows = toFuelRows(in.fuels());
        Map<Long, Double> given = fuel.givenByFuel(rows);

        List<FuelConsumption> fuels = new ArrayList<>();
        double totalNorm = 0d;
        for (FuelNorm norm : norms) {
            double givenLiters = given.getOrDefault(norm.fuelId(), 0d);
            FuelRow row = rows.stream().filter(r -> r.fuelId() == norm.fuelId()).findFirst().orElse(null);

            double normLiters = 0d;
            // §1.5: при нулевом выданном топливе норматив 0; §2.1: считается только при ненулевом пробеге.
            if (givenLiters != 0d && distanceKm > 0) {
                FuelNormRequest request = FuelNormRequest.builder()
                        .fuelId(norm.fuelId())
                        .baseNorm100(norm.consumption())
                        .distanceKm(distanceKm)
                        .excludingCoef(excludingCoef)
                        .additionalFuel100(nz(in.routeAdditionalFuel100()))
                        .additionalFuel(nz(in.routeAdditionalFuel()))
                        .routeCondFuel(nz(in.routeCondFuel()))
                        .routeHeatingFuel(nz(in.routeHeatingFuel()))
                        .coefficientMultiplier(coef.multiplier())
                        .conditionerHours(conditionerHours)
                        .workHours(workHours)
                        .airConditionerPercent(in.airConditionerPercent())
                        .interiorHeatingPerHour(brand != null && interiorHeatingApplies(coef)
                                ? brand.interiorHeatingPerHour() : 0d)
                        .build();
                normLiters = fuel.calcNorm(request).normLiters();
            }

            double additional = row == null ? 0d : row.additional();
            double remainBefore = row == null ? 0d : row.remainFuelBeforeExit();
            double remainEntry = FuelNormCalculator.remainFuelEntry(
                    remainBefore, givenLiters + additional, normLiters);

            normLiters = CalcUtils.round(normLiters, CalcUtils.FUEL_SCALE);
            totalNorm += normLiters;
            fuels.add(new FuelConsumption(norm.fuelId(), givenLiters, additional,
                    normLiters, remainBefore, remainEntry));
        }

        DriverSalary salary = WaybillMath.driverSalary(in.earning(), in.companyPercentIncome(),
                WaybillMath.classBonus(in.companyCat1(), in.companyCat2(), in.companyCat3(), in.driverDegree()));

        PassengerMetrics metrics = passengerMetrics(in, distanceKm);

        TariffAmount tariff = TariffMath.calculate(in.tariffPricePer1Mkm(), in.tariffPriceOneTime(),
                in.brandCostServices(), distanceKm, (int) Math.max(in.numberLap(), 0), 0);

        return new PassengerCalcResult(distanceKm, workMinutes, workHours, coef, fuels,
                CalcUtils.round(totalNorm, CalcUtils.FUEL_SCALE), salary, metrics, tariff);
    }

    // ============================================================ грузовая ветка

    /** Первые цифры кода марки, соответствующие бортовому кузову. */
    private static final String BODY_LABADOR = "124";
    private static final char BODY_SELF_UNLOAD = '3';
    private static final char BODY_SPECIAL = '5';
    private static final char BODY_SPECIAL_MOVER = '8';
    /** Кузов, для которого расход не нормируется. */
    private static final char BODY_NONE = '9';
    /** Позиция признака прицепа в коде марки. */
    private static final int TRAILER_FLAG_POSITION = 4;

    /**
     * Расчёт грузового путевого листа (формы 2-Б, 5Б-БМ) — перенос
     * {@code WaybillFuelService.calcCargo} + {@code cargoNorm}.
     *
     * @param in вход расчёта
     * @return сводный результат
     */
    public CargoCalcResult cargo(CargoCalcInput in) {
        if (in == null) {
            throw new IllegalArgumentException("Вход расчёта не задан");
        }

        int distanceKm = WaybillMath.distanceNonNegative(in.odometerEntry(), in.odometerExit());
        LocalDate calcDate = in.calcDate() != null ? in.calcDate() : LocalDate.now();

        DirectionCoefRef direction = new DirectionCoefRef(
                in.directionWinterCoefId(), in.directionMountainCoefId(), in.directionInCityCoefId());
        CoefficientBreakdown coef = in.applyCoefficient()
                ? coefficients.cargoCoefficient(direction, in.vehicleYearManufacture(), in.odometerExit(), calcDate)
                : CoefficientBreakdown.neutral();
        double multiplier = coef.multiplier();

        BrandNorms brand = brandNorms.forName(in.brandName());
        List<FuelNorm> norms = fuel.resolveNorms(brand, false);
        char body = bodyType(in.brandNumber());
        boolean hasTrailer = hasTrailer(in.brandNumber());

        List<FuelConsumption> fuels = new ArrayList<>();
        double totalNorm = 0d;
        for (CalcFuelLine line : in.fuels()) {
            Optional<FuelNorm> norm = fuel.findNorm(norms, line.fuelId());
            double normLiters;
            if (norm.isEmpty()) {
                log.warn("Норматив расхода вида топлива {} у марки «{}» не задан — норма нулевая",
                        line.fuelId(), in.brandName());
                normLiters = 0d;
            } else {
                CargoFuelRequest request = CargoFuelRequest.builder(norm.get())
                        .distanceKm(distanceKm)
                        .transportWork(in.transportWork())
                        .trips(in.trips())
                        .coefficient(multiplier)
                        .hasTrailer(hasTrailer)
                        .trailerWeight(in.trailerWeight())
                        .trailerCarrying(in.trailerCarrying())
                        .trailerWeight2(in.trailerWeight2())
                        .specialWorkHours(in.specialWorkHours())
                        .specialDistance(in.specialDistance())
                        .build();
                normLiters = cargoNorm(body, request);
            }
            double remainEntry = FuelNormCalculator.remainFuelEntry(
                    line.remainFuelBeforeExit(), line.fuelGiven() + line.additional(), normLiters);
            normLiters = CalcUtils.round(normLiters, CalcUtils.FUEL_SCALE);
            totalNorm += normLiters;
            fuels.add(new FuelConsumption(line.fuelId(), line.fuelGiven(), line.additional(),
                    normLiters, line.remainFuelBeforeExit(), remainEntry));
        }

        DriverSalary salary = WaybillMath.driverSalary(in.earning(), in.companyPercentIncome(),
                WaybillMath.classBonus(in.companyCat1(), in.companyCat2(), in.companyCat3(), in.driverDegree()));

        return new CargoCalcResult(distanceKm, coef, bodyName(body), hasTrailer,
                in.transportWork(), in.trips(), fuels,
                CalcUtils.round(totalNorm, CalcUtils.FUEL_SCALE), salary);
    }

    /** Выбор расчётной формулы по типу кузова (первая цифра кода марки). */
    private double cargoNorm(char body, CargoFuelRequest request) {
        if (body == BODY_NONE) {
            return 0d;
        }
        if (BODY_LABADOR.indexOf(body) >= 0) {
            return fuel.labadorFuel(request);
        }
        if (body == BODY_SELF_UNLOAD) {
            return fuel.selfUnloadFuel(request);
        }
        if (body == BODY_SPECIAL) {
            return fuel.specialFuel(request);
        }
        if (body == BODY_SPECIAL_MOVER) {
            return fuel.specialMoverFuel(request);
        }
        log.warn("Тип кузова '{}' не распознан — применён бортовой расчёт", body);
        return fuel.labadorFuel(request);
    }

    private static String bodyName(char body) {
        if (body == BODY_NONE) return "NONE";
        if (BODY_LABADOR.indexOf(body) >= 0) return "LABADOR";
        if (body == BODY_SELF_UNLOAD) return "SELF_UNLOAD";
        if (body == BODY_SPECIAL) return "SPECIAL";
        if (body == BODY_SPECIAL_MOVER) return "SPECIAL_MOVER";
        return "LABADOR";
    }

    private static char bodyType(String brandNumber) {
        return brandNumber == null || brandNumber.isBlank() ? BODY_NONE : brandNumber.charAt(0);
    }

    /** Наличие прицепа — пятый символ кода марки (цифра > 0). */
    private static boolean hasTrailer(String brandNumber) {
        if (brandNumber == null || brandNumber.length() <= TRAILER_FLAG_POSITION) {
            return false;
        }
        char flag = brandNumber.charAt(TRAILER_FLAG_POSITION);
        return Character.isDigit(flag) && Character.getNumericValue(flag) > 0;
    }

    // ------------------------------------------------ пассажирские показатели (§2.1)

    private PassengerMetrics passengerMetrics(PassengerCalcInput in, int distanceKm) {
        boolean noRoute = in.routeDistanceA() == null && in.routeDistanceB() == null
                && in.routePlannedLap() == null && in.routeCoeUseCapacity() == null;
        if (noRoute) {
            log.warn("Пассажирские показатели: маршрут не задан — нули");
            return PassengerMetrics.empty();
        }

        double routeDistance = (nz(in.routeDistanceA()) + nz(in.routeDistanceB())) / 2d;
        long laps = in.numberLap() <= 0 ? 0L : in.numberLap();

        double turnover = nz(in.capacity() == null ? null : in.capacity().doubleValue())
                * nz(in.routeCoeUseCapacity())
                * routeDistance
                * laps;
        double seatLength = nz(in.routeAverageLengthPassSeat());
        double passengerCount = CalcUtils.safeDivide(turnover, seatLength == 0d ? 1d : seatLength, 0d);

        double lPass = Math.max(routeDistance * laps, 0d);
        double totalDistance = in.speedometerTotalDistance()
                ? Math.max(WaybillMath.distance(in.odometerEntry(), in.odometerExit()), 0)
                : (lPass > 0 ? lPass + nz(in.routeBeginPathA()) + nz(in.routeBeginPathB()) : 0d);

        return new PassengerMetrics(
                Math.max(in.workDays(), 1),
                laps,
                nz(in.routePlannedLap() == null ? null : in.routePlannedLap().doubleValue()),
                turnover,
                passengerCount,
                lPass,
                totalDistance,
                Math.max(in.workTimeMinutes(), 0),
                money(in.earning()),
                money(in.kassa()),
                in.speedometerTotalDistance());
    }

    private static List<FuelRow> toFuelRows(List<CalcFuelLine> lines) {
        List<FuelRow> rows = new ArrayList<>();
        if (lines == null) {
            return rows;
        }
        for (CalcFuelLine l : lines) {
            rows.add(new FuelRow(l.fuelId(), l.fuelGiven(), l.coefBelow0(), l.additional(),
                    l.remainFuelBeforeExit(), 0d, 0d));
        }
        return rows;
    }

    private static double nz(Double value) {
        return value == null ? 0d : value;
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
