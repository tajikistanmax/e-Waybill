package tj.mintrans.epd.waybill.calc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tj.mintrans.epd.waybill.calc.model.BrandNorms;
import tj.mintrans.epd.waybill.calc.model.CalcFuelLine;
import tj.mintrans.epd.waybill.calc.model.CargoCalcInput;
import tj.mintrans.epd.waybill.calc.model.CargoCalcResult;
import tj.mintrans.epd.waybill.calc.model.SimpleCoefRef;

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
 * Тесты грузового оркестратора (формы 2-Б / 5Б-БМ) — портированы из rohkhat-v2
 * ({@code FuelCalculationServiceTest#Cargo} + {@code WaybillFuelService.calcCargo}).
 */
class WaybillCalcEngineCargoTest {

    private static final double EPS = 1e-9;

    private CoefficientDictionaries dict;
    private BrandNormsProvider brandNorms;
    private WaybillCalcEngine engine;

    // норма: consumption 30, ton_for_100 1.3, s_for_rais 0.5, work_for_hour 2, consumption_for_special 4
    private static final String FUEL_100 = """
            [{"fuel_id":2,"consumption":30,"ton_for_100":1.3,"s_for_rais":0.5,\
            "work_for_hour":2,"consumption_for_special":4}]""";

    @BeforeEach
    void setUp() {
        dict = mock(CoefficientDictionaries.class);
        brandNorms = mock(BrandNormsProvider.class);
        lenient().when(dict.winterCoef(anyLong())).thenReturn(Optional.empty());
        lenient().when(dict.cityCoef(anyLong())).thenReturn(Optional.empty());
        lenient().when(dict.usedCoefRows()).thenReturn(List.of());
        lenient().when(dict.driveClasses()).thenReturn(List.of());
        // Направление ссылается на ключ горного справочника 5 → значение 10 % → K=10 → множитель 1.1
        lenient().when(dict.mountainCoef(anyLong())).thenReturn(Optional.empty());
        lenient().when(dict.mountainCoef(5L)).thenReturn(Optional.of(new SimpleCoefRef(5L, 10)));
        lenient().when(brandNorms.forName(any())).thenReturn(new BrandNorms(40L, FUEL_100, null, null, 0d));

        engine = new WaybillCalcEngine(new CoefficientCalculator(dict), new FuelNormCalculator(), brandNorms);
    }

    private CargoCalcInput.Builder base(String brandNumber) {
        return CargoCalcInput.builder()
                .brandName("Тест")
                .brandNumber(brandNumber)
                .vehicleYearManufacture(LocalDate.of(2020, 1, 1))
                .directionMountainCoefId(5L)   // → множитель 1.1
                .applyCoefficient(true)
                .odometerExit(100_000L).odometerEntry(100_200L)   // пробег 200
                .calcDate(LocalDate.of(2024, 6, 15))
                .addFuel(new CalcFuelLine(2L, 80d, 0d, 0d, 10d));
    }

    @Test
    @DisplayName("бортовой (код 1xxxx): 0.01*(30*200 + 1.3*1000)*1.1 = 80.3, остаток 9.7")
    void labador() {
        CargoCalcResult r = engine.cargo(base("10000").transportWork(1000d).build());

        assertThat(r.bodyType()).isEqualTo("LABADOR");
        assertThat(r.hasTrailer()).isFalse();
        assertThat(r.coefficients().multiplier()).isCloseTo(1.1d, within(EPS));
        assertThat(r.fuels().getFirst().normLiters()).isEqualTo(80.3d);
        assertThat(r.fuels().getFirst().remainEntry()).isEqualTo(9.7d);
        assertThat(r.totalNormLiters()).isEqualTo(80.3d);
    }

    @Test
    @DisplayName("самосвал (код 3xxxx): + s_for_rais*Z = 0.5*4 → 82.3")
    void selfUnload() {
        CargoCalcResult r = engine.cargo(base("30000").transportWork(1000d).trips(4d).build());

        assertThat(r.bodyType()).isEqualTo("SELF_UNLOAD");
        assertThat(r.fuels().getFirst().normLiters()).isEqualTo(82.3d);
    }

    @Test
    @DisplayName("спецтехника на стоянке (код 5xxxx): (0.01*30*200 + 2*3)*1.1 = 72.6")
    void special() {
        CargoCalcResult r = engine.cargo(base("50000").specialWorkHours(3d).build());

        assertThat(r.bodyType()).isEqualTo("SPECIAL");
        assertThat(r.fuels().getFirst().normLiters()).isEqualTo(72.6d);
    }

    @Test
    @DisplayName("спецтехника в движении (код 8xxxx): 0.01*(30*200 + 4*150)*1.1 + 0.5*4 = 74.6")
    void specialMover() {
        CargoCalcResult r = engine.cargo(base("80000").specialDistance(150d).trips(4d).build());

        assertThat(r.bodyType()).isEqualTo("SPECIAL_MOVER");
        assertThat(r.fuels().getFirst().normLiters()).isEqualTo(74.6d);
    }

    @Test
    @DisplayName("форма 5Б-БМ: коэффициент = 1, два прицепа (5-я цифра кода = 1)")
    void bbmWithTwoTrailers() {
        CargoCalcResult r = engine.cargo(base("10001")
                .applyCoefficient(false)
                .transportWork(1000d)
                .trailerWeight(8d).trailerWeight2(6d)
                .build());

        assertThat(r.hasTrailer()).isTrue();
        assertThat(r.coefficients().multiplier()).isEqualTo(1d);
        // 0.01*(6000+1300)*1 + 8*200/100*1.3 + 6*200/100*1.3 = 73 + 20.8 + 15.6
        assertThat(r.fuels().getFirst().normLiters()).isEqualTo(109.4d);
    }

    @Test
    @DisplayName("кузов 9xxxx не нормируется: норма 0")
    void bodyNone() {
        CargoCalcResult r = engine.cargo(base("90000").transportWork(1000d).build());

        assertThat(r.bodyType()).isEqualTo("NONE");
        assertThat(r.fuels().getFirst().normLiters()).isZero();
    }

    @Test
    @DisplayName("норматив марки отсутствует: норма 0 без исключения")
    void missingNorm() {
        when(brandNorms.forName(any())).thenReturn(null);

        CargoCalcResult r = engine.cargo(base("10000").transportWork(1000d).build());

        assertThat(r.fuels().getFirst().normLiters()).isZero();
    }

    @Test
    @DisplayName("заработок водителя считается и для грузового листа")
    void salary() {
        CargoCalcResult r = engine.cargo(base("10000")
                .transportWork(1000d)
                .earning(new BigDecimal("1000")).companyPercentIncome(0.5d)
                .driverDegree(1).companyCat1((short) 100)
                .build());

        assertThat(r.salary().salary()).isEqualByComparingTo("475.00");
    }
}
