package tj.mintrans.epd.waybill.calc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tj.mintrans.epd.waybill.calc.model.BrandNorms;
import tj.mintrans.epd.waybill.calc.model.CalcFuelLine;
import tj.mintrans.epd.waybill.calc.model.PassengerCalcInput;
import tj.mintrans.epd.waybill.calc.model.PassengerCalcResult;
import tj.mintrans.epd.waybill.calc.model.SimpleCoefRef;
import tj.mintrans.epd.waybill.calc.model.WinterCoefRef;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Тесты оркестратора пассажирского расчёта — портированы из rohkhat-v2
 * ({@code WaybillCalculationServiceTest#FullCalculation}).
 */
class WaybillCalcEngineTest {

    private CoefficientDictionaries dict;
    private BrandNormsProvider brandNorms;
    private WaybillCalcEngine engine;

    @BeforeEach
    void setUp() {
        dict = mock(CoefficientDictionaries.class);
        brandNorms = mock(BrandNormsProvider.class);
        lenient().when(dict.winterCoef(anyLong())).thenReturn(Optional.empty());
        lenient().when(dict.mountainCoef(anyLong())).thenReturn(Optional.empty());
        lenient().when(dict.cityCoef(anyLong())).thenReturn(Optional.empty());
        lenient().when(dict.usedCoefRows()).thenReturn(List.of());
        lenient().when(dict.driveClasses()).thenReturn(List.of());
        // Зимняя запись id=1: период через новый год, надбавка 5 %.
        lenient().when(dict.winterCoef(1L)).thenReturn(Optional.of(
                new WinterCoefRef(1L, LocalDate.of(2000, 11, 1), LocalDate.of(2001, 3, 1), 5)));
        lenient().when(brandNorms.forName(any())).thenReturn(
                new BrandNorms(40L, "[{\"fuel_id\":2,\"consumption\":30}]",
                        "[{\"fuel_id\":2,\"consumption\":25}]", null, 0d));

        engine = new WaybillCalcEngine(new CoefficientCalculator(dict), new FuelNormCalculator(), brandNorms);
    }

    private PassengerCalcInput.Builder baseInput() {
        return PassengerCalcInput.builder()
                .brandName("МАЗ 103")
                .vehicleYearManufacture(LocalDate.of(2020, 1, 1))
                .airConditionerPercent(0)
                .capacity(100)
                .routeWinterCoefId(1L)
                .routeMountainCoefValue(2L)     // ЗНАЧЕНИЕ
                .routeInCityCoefValue(3L)       // ЗНАЧЕНИЕ
                .routeStationCoef(2)
                .routeRoadQuality(1)
                .routeAdditionalFuel100(1d)
                .routeAdditionalFuel(2d)
                .routeCondFuel(5d)
                .routeHeatingFuel(3d)
                .routeDistanceA(12d).routeDistanceB(12d).routePlannedLap(8).routeCoeUseCapacity(0.7d)
                .routeAverageLengthPassSeat(4d)
                .odometerExit(100_000L)
                .odometerEntry(100_200L)
                .workTimeMinutes(480)
                .numberLap(6)
                .calcDate(LocalDate.of(2024, 12, 15))
                .addFuel(new CalcFuelLine(2L, 80d, 0d, 0d, 10d))
                .earning(new BigDecimal("1000"))
                .companyPercentIncome(0.5d)
                .driverDegree(1)
                .companyCat1((short) 100);
    }

    @Test
    @DisplayName("нормальный случай: K=11, множитель 1.11, Ma=68.82, остаток 21.18, зарплата 475.00")
    void normal() {
        PassengerCalcResult r = engine.passenger(baseInput().build());

        assertThat(r.distanceKm()).isEqualTo(200);
        assertThat(r.workHours()).isEqualTo(8.0);
        assertThat(r.coefficients().k()).isEqualTo(11);
        assertThat(r.coefficients().multiplier()).isCloseTo(1.11d, within(1e-9));

        assertThat(r.fuels()).hasSize(1);
        var fuel = r.fuels().getFirst();
        assertThat(fuel.fuelId()).isEqualTo(2L);
        assertThat(fuel.given()).isCloseTo(80d, within(1e-9));
        assertThat(fuel.normLiters()).isEqualTo(68.82d);
        assertThat(fuel.remainEntry()).isEqualTo(21.18d);
        assertThat(r.totalNormLiters()).isEqualTo(68.82d);

        assertThat(r.salary().salary()).isEqualByComparingTo("475.00");
    }

    /** Марка с отоплением салона 2.5 л/ч (как brands id=1 в боевом справочнике). */
    private void brandWithInteriorHeating() {
        lenient().when(brandNorms.forName(any())).thenReturn(
                new BrandNorms(40L, "[{\"fuel_id\":2,\"consumption\":30}]",
                        "[{\"fuel_id\":2,\"consumption\":25}]", null, 2.5d));
    }

    @Test
    @DisplayName("отопление салона OFF (по умолчанию, как legacy warm_salon=0): норма 68.82 без надбавки даже зимой")
    void interiorHeatingOffByDefault() {
        brandWithInteriorHeating();
        PassengerCalcResult r = engine.passenger(baseInput().build()); // 15 декабря, зима активна, 8 ч

        assertThat(r.fuels().getFirst().normLiters()).isEqualTo(68.82d);
    }

    @Test
    @DisplayName("отопление салона WINTER: зимой +2.5·8 = 20 л (88.82), вне сезона — без надбавки")
    void interiorHeatingWinterOnly() {
        brandWithInteriorHeating();
        WaybillCalcEngine winter = new WaybillCalcEngine(new CoefficientCalculator(dict),
                new FuelNormCalculator(), brandNorms, InteriorHeatingMode.WINTER);

        PassengerCalcResult december = winter.passenger(baseInput().build());
        assertThat(december.coefficients().winterCoef()).isEqualTo(5);
        assertThat(december.fuels().getFirst().normLiters()).isEqualTo(88.82d);

        // июль: K = 2+3+2+0−1 = 6 → 0.01·31·200·1.06 = 65.72, отопления нет
        PassengerCalcResult july = winter.passenger(baseInput().calcDate(LocalDate.of(2024, 7, 15)).build());
        assertThat(july.coefficients().winterCoef()).isZero();
        assertThat(july.fuels().getFirst().normLiters()).isEqualTo(65.72d);
    }

    @Test
    @DisplayName("отопление салона ALWAYS (прежнее поведение): +20 л и в июле (85.72)")
    void interiorHeatingAlways() {
        brandWithInteriorHeating();
        WaybillCalcEngine always = new WaybillCalcEngine(new CoefficientCalculator(dict),
                new FuelNormCalculator(), brandNorms, InteriorHeatingMode.ALWAYS);

        PassengerCalcResult july = always.passenger(baseInput().calcDate(LocalDate.of(2024, 7, 15)).build());
        assertThat(july.fuels().getFirst().normLiters()).isEqualTo(85.72d);
    }

    @Test
    @DisplayName("ветка Душанбе не зависит от режима отопления: heating_fuel маршрута, Ma=64 при ALWAYS")
    void interiorHeatingDoesNotTouchDushanbeBranch() {
        brandWithInteriorHeating();
        WaybillCalcEngine always = new WaybillCalcEngine(new CoefficientCalculator(dict),
                new FuelNormCalculator(), brandNorms, InteriorHeatingMode.ALWAYS);

        PassengerCalcResult r = always.passenger(baseInput().routeExcludingCoef(true).build());
        assertThat(r.fuels().getFirst().normLiters()).isEqualTo(64d);
    }

    @Test
    @DisplayName("ветка excluding_coef: душанбинская норма 25, коэффициенты не применяются, Ma=64")
    void excludingCoefBranch() {
        PassengerCalcResult r = engine.passenger(baseInput().routeExcludingCoef(true).build());

        assertThat(r.coefficients().multiplier()).isEqualTo(1d);
        assertThat(r.coefficients().k()).isZero();
        assertThat(r.fuels().getFirst().normLiters()).isEqualTo(64d);
    }

    @Test
    @DisplayName("нулевой пробег: норматив 0, остаток = выдано + остаток до выезда")
    void zeroDistance() {
        PassengerCalcResult r = engine.passenger(baseInput().odometerEntry(100_000L).build());

        assertThat(r.distanceKm()).isZero();
        assertThat(r.fuels().getFirst().normLiters()).isZero();
        assertThat(r.fuels().getFirst().remainEntry()).isEqualTo(90d);
        assertThat(r.totalNormLiters()).isZero();
    }

    @Test
    @DisplayName("топливо не выдавалось: норматив 0")
    void noFuelGiven() {
        PassengerCalcResult r = engine.passenger(
                baseInput().fuels(List.of(new CalcFuelLine(2L, 0d, 0d, 0d, 10d))).build());

        assertThat(r.fuels().getFirst().normLiters()).isZero();
        assertThat(r.fuels().getFirst().remainEntry()).isEqualTo(10d);
    }

    @Test
    @DisplayName("§5.6 старое-vs-новое: coef_below_0 прибавляется к выданному (helpers.php fuel_calc: 80+2=82), " +
            "additional входит в остаток при возврате: 10 + 82 + 3 − 68.82 = 26.18")
    void coefBelow0AndAdditionalAffectGivenAndRemain() {
        // Легаси-строка fuels[]: {"fuel_id":2,"fuel_given":80,"coef_below_0":2,"additional":3,"remain_fuel_before_exit":10}
        PassengerCalcResult r = engine.passenger(
                baseInput().fuels(List.of(new CalcFuelLine(2L, 80d, 2d, 3d, 10d))).build());

        var fuel = r.fuels().getFirst();
        // fuel_calc(): $fuel = fuel_given; if (coef_below_0) $fuel += coef_below_0  → 82
        assertThat(fuel.given()).isCloseTo(82d, within(1e-9));
        assertThat(fuel.additional()).isCloseTo(3d, within(1e-9));
        // норматив от coef_below_0/additional не зависит (тот же Ma, что в normal())
        assertThat(fuel.normLiters()).isEqualTo(68.82d);
        // остаток при возврате: before + (given + coef_below_0) + additional − Ma
        assertThat(fuel.remainEntry()).isEqualTo(26.18d);
        // регресс: с нулями (как подавал ассемблер до 21.09) остаток был бы 21.18 — см. normal()
    }

    @Test
    @DisplayName("летний лист: зимняя надбавка не применяется, K=6")
    void summerWaybillHasNoWinterCoef() {
        PassengerCalcResult r = engine.passenger(baseInput().calcDate(LocalDate.of(2024, 6, 15)).build());

        assertThat(r.coefficients().winterCoef()).isZero();
        assertThat(r.coefficients().k()).isEqualTo(6);
    }

    @Test
    @DisplayName("марка не найдена: расчёт без исключения, топливо пустое, множитель по коэффициентам")
    void missingBrand() {
        when(brandNorms.forName(any())).thenReturn(null);

        PassengerCalcResult r = engine.passenger(baseInput().build());

        assertThat(r.fuels()).isEmpty();
        assertThat(r.totalNormLiters()).isZero();
        assertThat(r.coefficients().k()).isEqualTo(11);
        assertThat(r.salary().salary()).isEqualByComparingTo("475.00");
    }

    @Test
    @DisplayName("пассажирские показатели: пассажирооборот и число пассажиров")
    void passengerMetrics() {
        PassengerCalcResult r = engine.passenger(baseInput().build());

        // routeDistance = (12 + 12) / 2 = 12 ; turnover = 100 * 0.7 * 12 * 6 = 5040
        assertThat(r.passengerMetrics().passengerTurnover()).isCloseTo(5040d, within(1e-9));
        // passengers = 5040 / 4 = 1260
        assertThat(r.passengerMetrics().passengerCount()).isCloseTo(1260d, within(1e-9));
        assertThat(r.passengerMetrics().laps()).isEqualTo(6);
        assertThat(r.passengerMetrics().routeDistanceKm()).isCloseTo(72d, within(1e-9)); // 12 * 6
    }

    @Test
    @DisplayName("грузовой коэффициент недоступен пассажирской ветке (значения маршрута, не ключи)")
    void routeUsesValuesNotKeys() {
        // Справочник city_coef не содержит ключа 3 — но маршрут хранит ЗНАЧЕНИЕ 3, не ключ.
        when(dict.cityCoef(anyLong())).thenReturn(Optional.empty());
        when(dict.mountainCoef(anyLong())).thenReturn(Optional.of(new SimpleCoefRef(99L, 999)));

        PassengerCalcResult r = engine.passenger(baseInput().build());

        assertThat(r.coefficients().mountainCoef()).isEqualTo(2);
        assertThat(r.coefficients().cityCoef()).isEqualTo(3);
    }
}
