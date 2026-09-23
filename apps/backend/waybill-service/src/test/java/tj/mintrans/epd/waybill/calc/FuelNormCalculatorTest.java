package tj.mintrans.epd.waybill.calc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tj.mintrans.epd.waybill.calc.model.BrandNorms;
import tj.mintrans.epd.waybill.calc.model.CargoFuelRequest;
import tj.mintrans.epd.waybill.calc.model.FuelNorm;
import tj.mintrans.epd.waybill.calc.model.FuelNormRequest;
import tj.mintrans.epd.waybill.calc.model.FuelNormResult;
import tj.mintrans.epd.waybill.calc.model.FuelRow;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Тесты расчёта нормативного расхода топлива (§1.3, §1.5, §1.6, §3.5 спецификации «Роҳхат»).
 * Портированы из rohkhat-v2 ({@code FuelCalculationServiceTest}); {@code Brand} заменён на {@link BrandNorms}.
 */
class FuelNormCalculatorTest {

    private static final double EPS = 1e-9;

    private FuelNormCalculator service;

    @BeforeEach
    void setUp() {
        service = new FuelNormCalculator();
    }

    // -------------------------------------------------------------- разбор JSON

    @Nested
    @DisplayName("Разбор JSON нормативов и топлива")
    class Parsing {

        @Test
        @DisplayName("нормальный случай: числа-строки с запятой разбираются корректно")
        void parseNorms() {
            String json = """
                    [{"fuel_id":"2","consumption":"31,5","ton_for_100":"1,3",
                      "s_for_rais":"0,25","work_for_hour":"2","consumption_for_special":"3"}]""";

            List<FuelNorm> norms = service.parseNorms(json);

            assertThat(norms).hasSize(1);
            FuelNorm norm = norms.getFirst();
            assertThat(norm.fuelId()).isEqualTo(2L);
            assertThat(norm.consumption()).isCloseTo(31.5, within(EPS));
            assertThat(norm.tonFor100()).isCloseTo(1.3, within(EPS));
            assertThat(norm.sForRais()).isCloseTo(0.25, within(EPS));
            assertThat(norm.workForHour()).isCloseTo(2d, within(EPS));
            assertThat(norm.consumptionForSpecial()).isCloseTo(3d, within(EPS));
        }

        @Test
        @DisplayName("некорректный/пустой JSON не приводит к исключению")
        void parseNormsInvalid() {
            assertThat(service.parseNorms(null)).isEmpty();
            assertThat(service.parseNorms("")).isEmpty();
            assertThat(service.parseNorms("{не json}")).isEmpty();
            assertThat(service.parseNorms("{\"fuel_id\":1}")).isEmpty();
            assertThat(service.parseNorms("[{\"consumption\":30}]")).isEmpty();
        }

        @Test
        @DisplayName("строки топлива: отсутствующие поля дают 0")
        void parseFuelRows() {
            String json = """
                    [{"fuel_id":1,"fuel_given":50,"coef_below_0":2,"additional":3,
                      "remain_fuel_before_exit":10}]""";

            List<FuelRow> rows = service.parseFuelRows(json);

            assertThat(rows).hasSize(1);
            FuelRow row = rows.getFirst();
            assertThat(row.fuelId()).isEqualTo(1L);
            assertThat(row.fuelGiven()).isCloseTo(50d, within(EPS));
            assertThat(row.coefBelow0()).isCloseTo(2d, within(EPS));
            assertThat(row.additional()).isCloseTo(3d, within(EPS));
            assertThat(row.remainFuelBeforeExit()).isCloseTo(10d, within(EPS));
            assertThat(row.remainFuelEntry()).isZero();
            assertThat(row.consumption()).isZero();
        }

        @Test
        @DisplayName("null-значения в JSON трактуются как 0")
        void parseFuelRowsWithNulls() {
            List<FuelRow> rows = service.parseFuelRows(
                    "[{\"fuel_id\":2,\"fuel_given\":null,\"coef_below_0\":null}]");

            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst().fuelGiven()).isZero();
        }
    }

    // ------------------------------------------------------------------- выданное

    @Nested
    @DisplayName("fuel_calc(): выданное топливо по видам")
    class GivenByFuel {

        @Test
        @DisplayName("нормальный случай: fuel_given + coef_below_0, ключи 1/2/3 всегда присутствуют")
        void normal() {
            Map<Long, Double> given = service.givenByFuel(List.of(
                    new FuelRow(1L, 50d, 2d, 0d, 0d, 0d, 0d),
                    new FuelRow(2L, 80d, 0d, 0d, 0d, 0d, 0d)));

            assertThat(given).containsOnlyKeys(1L, 2L, 3L);
            assertThat(given.get(1L)).isCloseTo(52d, within(EPS));
            assertThat(given.get(2L)).isCloseTo(80d, within(EPS));
            assertThat(given.get(3L)).isZero();
        }

        @Test
        @DisplayName("граничный случай: пустой/null список даёт нули по всем видам")
        void empty() {
            assertThat(service.givenByFuel(null)).containsValues(0d, 0d, 0d);
            assertThat(service.givenByFuel(List.of())).containsValues(0d, 0d, 0d);
        }

        @Test
        @DisplayName("вид топлива вне диапазона 1..3 игнорируется — как в оригинале")
        void unknownFuelIdIgnored() {
            Map<Long, Double> given = service.givenByFuel(List.of(FuelRow.ofGiven(7L, 99d)));

            assertThat(given).containsOnlyKeys(1L, 2L, 3L);
            assertThat(given.values()).allMatch(v -> v == 0d);
        }
    }

    // --------------------------------------------------------- нормативы марки

    @Nested
    @DisplayName("Выбор таблицы нормативов марки")
    class ResolveNorms {

        private BrandNorms brand() {
            return new BrandNorms(40L,
                    "[{\"fuel_id\":2,\"consumption\":30}]",
                    "[{\"fuel_id\":2,\"consumption\":25}]",
                    null, 0d);
        }

        @Test
        @DisplayName("excluding_coef = true -> fuel_100_dushanbe, иначе fuel_100")
        void picksTable() {
            assertThat(service.resolveNorms(brand(), false).getFirst().consumption())
                    .isCloseTo(30d, within(EPS));
            assertThat(service.resolveNorms(brand(), true).getFirst().consumption())
                    .isCloseTo(25d, within(EPS));
        }

        @Test
        @DisplayName("марка не найдена или fuel_100 пуст -> пустой список без исключения")
        void missingBrand() {
            assertThat(service.resolveNorms(null, false)).isEmpty();
            assertThat(service.resolveNorms(BrandNorms.empty(41L), false)).isEmpty();
        }

        @Test
        @DisplayName("поиск норматива по виду топлива")
        void findNorm() {
            List<FuelNorm> norms = service.resolveNorms(brand(), false);

            assertThat(service.findNorm(norms, 2L)).isPresent();
            assertThat(service.findNorm(norms, 1L)).isEmpty();
            assertThat(service.findNorm(null, 2L)).isEmpty();
        }

        @Test
        @DisplayName("дубль fuel_id (как у 118 марок legacy) -> одна строка: позиция первой, норма последней (array_column)")
        void duplicateFuelIdLastWins() {
            // Реальная марка legacy «Кинг Лонг - 6900»: дизель дважды — 45 и 40 л/100 км; и бензин.
            BrandNorms dup = new BrandNorms(25L,
                    "[{\"fuel_id\":\"2\",\"consumption\":\"45\"},{\"fuel_id\":\"1\",\"consumption\":\"11\"},"
                            + "{\"fuel_id\":\"2\",\"consumption\":\"40\"}]",
                    null, null, 0d);

            List<FuelNorm> norms = service.resolveNorms(dup, false);

            assertThat(norms).extracting(FuelNorm::fuelId).containsExactly(2L, 1L);
            assertThat(norms.getFirst().consumption()).isCloseTo(40d, within(EPS));
        }
    }

    // ------------------------------------------------ нормативный расход (пасс.)

    @Nested
    @DisplayName("fuel_calc_100(): нормативный расход")
    class Norm {

        @Test
        @DisplayName("ветка A (excluding_coef): 0.01*(30+1+2)*100 + 5 + 3 = 41")
        void branchA() {
            FuelNormResult result = service.calcNorm(FuelNormRequest.builder()
                    .fuelId(2L).baseNorm100(30d).distanceKm(100d).excludingCoef(true)
                    .additionalFuel100(1d).additionalFuel(2d).routeCondFuel(5d).routeHeatingFuel(3d)
                    .build());

            assertThat(result.normLiters()).isCloseTo(41d, within(1e-9));
            assertThat(result.normPer100Km()).isCloseTo(33d, within(1e-9));
            assertThat(result.conditionerLiters()).isCloseTo(5d, within(EPS));
            assertThat(result.interiorHeatingLiters()).isCloseTo(3d, within(EPS));
        }

        @Test
        @DisplayName("ветка B (с коэффициентами): 0.01*(30+1)*100*1.2 = 37.2")
        void branchB() {
            FuelNormResult result = service.calcNorm(FuelNormRequest.builder()
                    .fuelId(2L).baseNorm100(30d).distanceKm(100d)
                    .additionalFuel100(1d).coefficientMultiplier(1.2d).workHours(8d)
                    .build());

            assertThat(result.normLiters()).isCloseTo(37.2d, within(1e-9));
            assertThat(result.normPer100Km()).isCloseTo(37.2d, within(1e-9));
            assertThat(result.conditionerLiters()).isZero();
            assertThat(result.interiorHeatingLiters()).isZero();
        }

        @Test
        @DisplayName("ветка B с надбавками кондиционера и отопления салона")
        void branchBWithAddons() {
            FuelNormResult result = service.calcNorm(FuelNormRequest.builder()
                    .fuelId(2L).baseNorm100(30d).distanceKm(100d).additionalFuel100(1d)
                    .coefficientMultiplier(1.2d).conditionerHours(4d).workHours(8d)
                    .airConditionerPercent(5).interiorHeatingPerHour(1.5d)
                    .build());

            // cond = (4/8) * (5*0.01) * 0.01 * 30 * 100 = 0.75 ; heat = 1.5 * 8 = 12
            assertThat(result.conditionerLiters()).isCloseTo(0.75d, within(1e-9));
            assertThat(result.interiorHeatingLiters()).isCloseTo(12d, within(1e-9));
            assertThat(result.normLiters()).isCloseTo(37.2d + 0.75d + 12d, within(1e-9));
        }

        @Test
        @DisplayName("ветка B со спецработой: надбавка прибавляется к нормативу")
        void branchBWithSpecialWork() {
            FuelNormResult result = service.calcNorm(FuelNormRequest.builder()
                    .fuelId(2L).baseNorm100(30d).distanceKm(100d)
                    .coefficientMultiplier(1d).specialWorkFuel(6d)
                    .build());

            assertThat(result.specialWorkLiters()).isCloseTo(6d, within(EPS));
            assertThat(result.normLiters()).isCloseTo(36d, within(1e-9));
        }

        @Test
        @DisplayName("граничный случай: нулевой пробег даёт только надбавки")
        void zeroDistance() {
            FuelNormResult result = service.calcNorm(FuelNormRequest.builder()
                    .fuelId(2L).baseNorm100(30d).distanceKm(0d)
                    .coefficientMultiplier(1.2d).workHours(8d).interiorHeatingPerHour(1d)
                    .build());

            assertThat(result.normLiters()).isCloseTo(8d, within(1e-9));
        }

        @Test
        @DisplayName("граничный случай: отрицательный результат обнуляется (Ma > 0 ? Ma : 0)")
        void negativeClampedToZero() {
            FuelNormResult result = service.calcNorm(FuelNormRequest.builder()
                    .fuelId(2L).baseNorm100(30d).additionalFuel100(-50d).distanceKm(100d)
                    .coefficientMultiplier(1d)
                    .build());

            assertThat(result.normLiters()).isZero();
        }

        @Test
        @DisplayName("отсутствие коэффициента: множитель 1 по умолчанию")
        void defaultMultiplierIsOne() {
            FuelNormResult result = service.calcNorm(FuelNormRequest.builder()
                    .fuelId(2L).baseNorm100(30d).distanceKm(100d)
                    .build());

            assertThat(result.normLiters()).isCloseTo(30d, within(1e-9));
        }

        @Test
        @DisplayName("пустой запрос не приводит к NPE")
        void nullRequest() {
            assertThat(service.calcNorm(null).normLiters()).isZero();
        }

        @Test
        @DisplayName("fuel_use: перерасход/экономия относительно норматива")
        void fuelUse() {
            assertThat(FuelNormCalculator.fuelUse(50d, 40d)).isCloseTo(10d, within(EPS));
            assertThat(FuelNormCalculator.fuelUse(50d, 0d)).isCloseTo(50d, within(EPS));
        }
    }

    // -------------------------------------------------------------------- надбавки

    @Nested
    @DisplayName("Надбавки: кондиционер, отопление, почасовая норма")
    class Addons {

        @Test
        @DisplayName("кондиционер, нормальный случай: (4/8)*(5*0.01)*0.01*30*100 = 0.75")
        void conditioner() {
            assertThat(FuelNormCalculator.conditionerFuel(4d, 8d, 5, 30d, 100d))
                    .isCloseTo(0.75d, within(1e-9));
        }

        @Test
        @DisplayName("кондиционер, граничный случай: нулевое рабочее время не даёт деления на ноль")
        void conditionerZeroWorkTime() {
            assertThat(FuelNormCalculator.conditionerFuel(4d, 0d, 5, 30d, 100d)).isZero();
            assertThat(FuelNormCalculator.conditionerFuel(0d, 8d, 5, 30d, 100d)).isZero();
        }

        @Test
        @DisplayName("РАСХОЖДЕНИЕ: legacy-вариант возвращает безразмерную долю времени")
        void conditionerLegacy() {
            assertThat(FuelNormCalculator.conditionerFuelLegacy(4d, 8d)).isCloseTo(0.5d, within(EPS));
            assertThat(FuelNormCalculator.conditionerFuelLegacy(4d, 0d)).isZero();
            assertThat(FuelNormCalculator.conditionerFuel(4d, 8d, 5, 30d, 100d))
                    .isNotEqualTo(FuelNormCalculator.conditionerFuelLegacy(4d, 8d));
        }

        @Test
        @DisplayName("отопление салона: норма л/ч * часы работы")
        void interiorHeating() {
            assertThat(FuelNormCalculator.interiorHeatingFuel(1.5d, 8d)).isCloseTo(12d, within(EPS));
            assertThat(FuelNormCalculator.interiorHeatingFuel(0d, 8d)).isZero();
            assertThat(FuelNormCalculator.interiorHeatingFuel(1.5d, 0d)).isZero();
        }

        @Test
        @DisplayName("почасовая норма fuel_hour: consumption * часы")
        void hourlyNorm() {
            BrandNorms brand = new BrandNorms(40L, null, null, "[{\"fuel_id\":2,\"consumption\":3}]", 0d);
            assertThat(service.hourlyNorm(brand, 2L, 4d)).isCloseTo(12d, within(EPS));
        }

        @Test
        @DisplayName("почасовая норма: отсутствие данных даёт 0 без исключения")
        void hourlyNormMissing() {
            BrandNorms brand = new BrandNorms(40L, null, null, "[{\"fuel_id\":2,\"consumption\":3}]", 0d);

            assertThat(service.hourlyNorm(brand, 1L, 4d)).isZero();
            assertThat(service.hourlyNorm(brand, 2L, 0d)).isZero();
            assertThat(service.hourlyNorm(null, 2L, 4d)).isZero();
            assertThat(service.hourlyNorm(BrandNorms.empty(40L), 2L, 4d)).isZero();
        }
    }

    // ------------------------------------------------------------- грузовые формулы

    @Nested
    @DisplayName("Грузовые формулы 2-Б / 5Б-БМ")
    class Cargo {

        private final FuelNorm norm = new FuelNorm(2L, 30d, 1.3d, 0.5d, 2d, 4d);

        @Test
        @DisplayName("Labador: 0.01*(30*200 + 1.3*1000)*1.1 = 80.3")
        void labador() {
            double result = service.labadorFuel(CargoFuelRequest.builder(norm)
                    .distanceKm(200d).transportWork(1000d).coefficient(1.1d).build());

            assertThat(result).isCloseTo(80.3d, within(1e-9));
        }

        @Test
        @DisplayName("Labador с прицепом: += 8*200/100*1.3 = 20.8 (коэффициент к добавке НЕ применяется)")
        void labadorWithTrailer() {
            double result = service.labadorFuel(CargoFuelRequest.builder(norm)
                    .distanceKm(200d).transportWork(1000d).coefficient(1.1d)
                    .hasTrailer(true).trailerWeight(8d).build());

            assertThat(result).isCloseTo(80.3d + 20.8d, within(1e-9));
        }

        @Test
        @DisplayName("Labador: надбавка «фуругон» +10 % (в оригинале недостижима)")
        void labadorVanSurcharge() {
            double result = service.labadorFuel(CargoFuelRequest.builder(norm)
                    .distanceKm(200d).transportWork(1000d).coefficient(1d)
                    .vanSurcharge(true).build());

            assertThat(result).isCloseTo(73d * 1.1d, within(1e-9));
        }

        @Test
        @DisplayName("SelfUnload: 0.01*(30*200 + 1.3*1000)*1.1 + 0.5*4 = 82.3")
        void selfUnload() {
            double result = service.selfUnloadFuel(CargoFuelRequest.builder(norm)
                    .distanceKm(200d).transportWork(1000d).trips(4d).coefficient(1.1d).build());

            assertThat(result).isCloseTo(82.3d, within(1e-9));
        }

        @Test
        @DisplayName("SelfUnload с прицепом: += 1.3*(8 + 0.5*10) = 16.9 (без умножения на пробег)")
        void selfUnloadWithTrailer() {
            double result = service.selfUnloadFuel(CargoFuelRequest.builder(norm)
                    .distanceKm(200d).transportWork(1000d).trips(4d).coefficient(1.1d)
                    .hasTrailer(true).trailerWeight(8d).trailerCarrying(10d).build());

            assertThat(result).isCloseTo(82.3d + 16.9d, within(1e-9));
        }

        @Test
        @DisplayName("Special: (0.01*30*200 + 2*3)*1.1 = 72.6")
        void special() {
            double result = service.specialFuel(CargoFuelRequest.builder(norm)
                    .distanceKm(200d).specialWorkHours(3d).coefficient(1.1d).build());

            assertThat(result).isCloseTo(72.6d, within(1e-9));
        }

        @Test
        @DisplayName("Special с прицепом: += 1.3*1000/100 = 13")
        void specialWithTrailer() {
            double result = service.specialFuel(CargoFuelRequest.builder(norm)
                    .distanceKm(200d).transportWork(1000d).specialWorkHours(3d).coefficient(1.1d)
                    .hasTrailer(true).build());

            assertThat(result).isCloseTo(72.6d + 13d, within(1e-9));
        }

        @Test
        @DisplayName("SpecialMover: 0.01*(30*200 + 4*150)*1.1 + 0.5*4 = 74.6")
        void specialMover() {
            double result = service.specialMoverFuel(CargoFuelRequest.builder(norm)
                    .distanceKm(200d).specialDistance(150d).trips(4d).coefficient(1.1d).build());

            assertThat(result).isCloseTo(74.6d, within(1e-9));
        }

        @Test
        @DisplayName("граничный случай: нулевой пробег и нулевая работа дают 0")
        void zeroDistance() {
            CargoFuelRequest request = CargoFuelRequest.builder(norm).coefficient(1.1d).build();

            assertThat(service.labadorFuel(request)).isZero();
            assertThat(service.selfUnloadFuel(request)).isZero();
            assertThat(service.specialFuel(request)).isZero();
            assertThat(service.specialMoverFuel(request)).isZero();
        }

        @Test
        @DisplayName("отсутствие норматива марки: применяется 1 л/100 км, как в оригинале")
        void missingNorm() {
            double result = service.labadorFuel(CargoFuelRequest.builder(null)
                    .distanceKm(100d).coefficient(1d).build());

            assertThat(result).isCloseTo(1d, within(1e-9));
        }

        @Test
        @DisplayName("5Б-БМ: коэффициент не применяется (coef = 1) и учитывается второй прицеп")
        void bbmWithTwoTrailers() {
            double result = service.labadorFuel(CargoFuelRequest.builder(norm)
                    .distanceKm(200d).transportWork(1000d).coefficient(1d)
                    .hasTrailer(true).trailerWeight(8d).trailerWeight2(6d).build());

            // 0.01*(6000+1300)*1 + 8*2*1.3 + 6*2*1.3 = 73 + 20.8 + 15.6
            assertThat(result).isCloseTo(73d + 20.8d + 15.6d, within(1e-9));
        }
    }

    // --------------------------------------------------------------------- остаток

    @Nested
    @DisplayName("Остаток топлива")
    class Remain {

        @Test
        @DisplayName("нормальный случай: round(остаток_до + выдано - расход, 4)")
        void remainFuelEntry() {
            assertThat(FuelNormCalculator.remainFuelEntry(10d, 80d, 74.40000000000002d))
                    .isEqualTo(15.6d);
            assertThat(FuelNormCalculator.remainFuelEntry(0d, 0d, 0d)).isZero();
        }

        @Test
        @DisplayName("перерасход даёт отрицательный остаток (не обнуляется)")
        void negativeRemain() {
            assertThat(FuelNormCalculator.remainFuelEntry(0d, 10d, 25d)).isEqualTo(-15d);
        }

        @Test
        @DisplayName("РАСХОЖДЕНИЕ: legacy-формула хука имеет перевёрнутые знаки")
        void legacyRemainHasFlippedSigns() {
            double correct = FuelNormCalculator.remainFuelEntry(10d, 80d + 3d, 40d);
            double legacy = FuelNormCalculator.remainFuelEntryLegacy(40d, 3d, 80d, 10d);

            assertThat(correct).isEqualTo(53d);
            assertThat(legacy).isEqualTo(-27d);
        }
    }
}
