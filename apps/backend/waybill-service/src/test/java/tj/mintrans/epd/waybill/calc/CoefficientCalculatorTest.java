package tj.mintrans.epd.waybill.calc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tj.mintrans.epd.waybill.calc.model.CoefficientBreakdown;
import tj.mintrans.epd.waybill.calc.model.DirectionCoefRef;
import tj.mintrans.epd.waybill.calc.model.DriveClassRef;
import tj.mintrans.epd.waybill.calc.model.RouteCoefRef;
import tj.mintrans.epd.waybill.calc.model.SimpleCoefRef;
import tj.mintrans.epd.waybill.calc.model.UsedCoefRef;
import tj.mintrans.epd.waybill.calc.model.WinterCoefRef;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Тесты подбора коэффициентов (§1.4, §3.4, §10 спецификации «Роҳхат»).
 * Портированы из rohkhat-v2 ({@code CoefficientServiceTest}); пять репозиториев заменены
 * одним интерфейсом {@link CoefficientDictionaries}.
 */
class CoefficientCalculatorTest {

    private CoefficientDictionaries dict;
    private CoefficientCalculator calc;

    private static final LocalDate Y2020 = LocalDate.of(2020, 1, 1);

    @BeforeEach
    void setUp() {
        dict = mock(CoefficientDictionaries.class);
        lenient().when(dict.winterCoef(anyLong())).thenReturn(Optional.empty());
        lenient().when(dict.mountainCoef(anyLong())).thenReturn(Optional.empty());
        lenient().when(dict.cityCoef(anyLong())).thenReturn(Optional.empty());
        lenient().when(dict.usedCoefRows()).thenReturn(List.of());
        lenient().when(dict.driveClasses()).thenReturn(List.of());
        calc = new CoefficientCalculator(dict);
    }

    private static WinterCoefRef winter(LocalDate from, LocalDate to, Integer coef) {
        return new WinterCoefRef(1L, from, to, coef);
    }

    // ------------------------------------------------------------- зимний коэффициент

    @Nested
    @DisplayName("Зимний коэффициент")
    class Winter {

        @Test
        @DisplayName("нормальный случай: период через новый год, декабрьская дата")
        void insideWrappingPeriod() {
            when(dict.winterCoef(1L)).thenReturn(Optional.of(
                    winter(LocalDate.of(2000, 11, 1), LocalDate.of(2001, 3, 1), 5)));

            assertThat(calc.winterCoef(1L, LocalDate.of(2024, 12, 15))).isEqualTo(5);
        }

        @Test
        @DisplayName("граница периода включительно: 01 ноября и 01 марта")
        void boundariesInclusive() {
            when(dict.winterCoef(1L)).thenReturn(Optional.of(
                    winter(LocalDate.of(2000, 11, 1), LocalDate.of(2001, 3, 1), 5)));

            assertThat(calc.winterCoef(1L, LocalDate.of(2024, 11, 1))).isEqualTo(5);
            assertThat(calc.winterCoef(1L, LocalDate.of(2025, 3, 1))).isEqualTo(5);
        }

        @Test
        @DisplayName("день за границей периода: 31 октября и 2 марта дают 0")
        void justOutsideBoundaries() {
            when(dict.winterCoef(1L)).thenReturn(Optional.of(
                    winter(LocalDate.of(2000, 11, 1), LocalDate.of(2001, 3, 1), 5)));

            assertThat(calc.winterCoef(1L, LocalDate.of(2024, 10, 31))).isZero();
            assertThat(calc.winterCoef(1L, LocalDate.of(2025, 3, 2))).isZero();
        }

        @Test
        @DisplayName("летняя дата вне зимнего периода даёт 0")
        void summerDate() {
            when(dict.winterCoef(1L)).thenReturn(Optional.of(
                    winter(LocalDate.of(2000, 11, 1), LocalDate.of(2001, 3, 1), 5)));

            assertThat(calc.winterCoef(1L, LocalDate.of(2024, 6, 15))).isZero();
        }

        @Test
        @DisplayName("период внутри одного года обрабатывается корректно (в оригинале — [БАГ])")
        void intraYearPeriod() {
            when(dict.winterCoef(1L)).thenReturn(Optional.of(
                    winter(LocalDate.of(2000, 1, 10), LocalDate.of(2000, 3, 1), 7)));

            assertThat(calc.winterCoef(1L, LocalDate.of(2024, 2, 1))).isEqualTo(7);
            assertThat(calc.winterCoef(1L, LocalDate.of(2024, 6, 15))).isZero();
        }

        @Test
        @DisplayName("РАСХОЖДЕНИЕ: legacy-условие для периода внутри года даёт ложное срабатывание")
        void legacyConditionIsBuggy() {
            LocalDate from = LocalDate.of(2000, 1, 10);
            LocalDate to = LocalDate.of(2000, 3, 1);
            LocalDate june = LocalDate.of(2024, 6, 15);

            assertThat(CoefficientCalculator.isWinterPeriodLegacy(june, from, to)).isTrue();
            assertThat(CoefficientCalculator.isWinterPeriod(june, from, to)).isFalse();
        }

        @Test
        @DisplayName("legacy и корректный вариант совпадают для периода через новый год")
        void legacyMatchesForWrappingPeriod() {
            LocalDate from = LocalDate.of(2000, 11, 1);
            LocalDate to = LocalDate.of(2001, 3, 1);

            assertThat(CoefficientCalculator.isWinterPeriodLegacy(LocalDate.of(2024, 12, 15), from, to)).isTrue();
            assertThat(CoefficientCalculator.isWinterPeriodLegacy(LocalDate.of(2024, 6, 15), from, to)).isFalse();
        }

        @Test
        @DisplayName("отсутствие коэффициента: id null, запись не найдена, пустой период, пустой coef")
        void missingData() {
            assertThat(calc.winterCoef(null, LocalDate.of(2024, 12, 15))).isZero();
            assertThat(calc.winterCoef(99L, LocalDate.of(2024, 12, 15))).isZero();

            when(dict.winterCoef(1L)).thenReturn(Optional.of(winter(null, null, 5)));
            assertThat(calc.winterCoef(1L, LocalDate.of(2024, 12, 15))).isZero();

            when(dict.winterCoef(2L)).thenReturn(Optional.of(
                    new WinterCoefRef(2L, LocalDate.of(2000, 11, 1), LocalDate.of(2001, 3, 1), null)));
            assertThat(calc.winterCoef(2L, LocalDate.of(2024, 12, 15))).isZero();
        }

        @Test
        @DisplayName("дата не задана — коэффициент не применяется")
        void nullDate() {
            assertThat(calc.winterCoef(1L, null)).isZero();
        }
    }

    // ------------------------------------------------------- горный/городской

    @Nested
    @DisplayName("Горный и городской коэффициенты")
    class MountainAndCity {

        @Test
        @DisplayName("нормальный случай: берётся ЗНАЧЕНИЕ coef, а не идентификатор")
        void values() {
            when(dict.mountainCoef(2L)).thenReturn(Optional.of(new SimpleCoefRef(2L, 10)));
            when(dict.cityCoef(3L)).thenReturn(Optional.of(new SimpleCoefRef(3L, 4)));

            assertThat(calc.mountainCoef(2L)).isEqualTo(10);
            assertThat(calc.cityCoef(3L)).isEqualTo(4);
        }

        @Test
        @DisplayName("отсутствие коэффициента: null id и ненайденная запись дают 0")
        void missing() {
            assertThat(calc.mountainCoef(null)).isZero();
            assertThat(calc.mountainCoef(77L)).isZero();
            assertThat(calc.cityCoef(null)).isZero();
            assertThat(calc.cityCoef(77L)).isZero();
        }

        @Test
        @DisplayName("пустое поле coef даёт 0, а не NPE")
        void nullCoefField() {
            when(dict.mountainCoef(2L)).thenReturn(Optional.of(new SimpleCoefRef(2L, null)));
            assertThat(calc.mountainCoef(2L)).isZero();

            when(dict.cityCoef(3L)).thenReturn(Optional.of(new SimpleCoefRef(3L, null)));
            assertThat(calc.cityCoef(3L)).isZero();
        }
    }

    // ------------------------------------------------------------------- износ

    @Nested
    @DisplayName("Коэффициент износа")
    class Used {

        private UsedCoefRef used(int year, long km, int coef) {
            return new UsedCoefRef(year, km, coef);
        }

        @Test
        @DisplayName("нормальный случай: справочник used_coef, возраст > 8 и пробег > 150000 -> 10")
        void fromDictionaryHigh() {
            when(dict.usedCoefRows()).thenReturn(List.of(
                    used(8, 150_000, 10), used(5, 100_000, 5)));

            assertThat(calc.usedCoef(LocalDate.of(2010, 1, 1), 200_000L, LocalDate.of(2024, 6, 1)))
                    .isEqualTo(10);
        }

        @Test
        @DisplayName("средний порог: возраст > 5 и пробег > 100000 -> 5")
        void fromDictionaryLow() {
            when(dict.usedCoefRows()).thenReturn(List.of(
                    used(8, 150_000, 10), used(5, 100_000, 5)));

            assertThat(calc.usedCoef(LocalDate.of(2010, 1, 1), 120_000L, LocalDate.of(2024, 6, 1)))
                    .isEqualTo(5);
        }

        @Test
        @DisplayName("границы порогов строгие: возраст ровно 8 и пробег ровно 150000 не дают надбавку")
        void strictBoundaries() {
            when(dict.usedCoefRows()).thenReturn(List.of(used(8, 150_000, 10)));

            assertThat(calc.usedCoef(LocalDate.of(2016, 1, 1), 150_000L, LocalDate.of(2024, 6, 1)))
                    .isZero();
        }

        @Test
        @DisplayName("новый автомобиль -> 0")
        void newVehicle() {
            when(dict.usedCoefRows()).thenReturn(List.of(
                    used(8, 150_000, 10), used(5, 100_000, 5)));

            assertThat(calc.usedCoef(LocalDate.of(2022, 1, 1), 500_000L, LocalDate.of(2024, 6, 1)))
                    .isZero();
        }

        @Test
        @DisplayName("пустой справочник: применяются пороги оригинала (8/150000 и 5/100000)")
        void fallbackToOriginalConstants() {
            assertThat(calc.usedCoef(LocalDate.of(2010, 1, 1), 200_000L, LocalDate.of(2024, 6, 1)))
                    .isEqualTo(10);
            assertThat(calc.usedCoef(LocalDate.of(2010, 1, 1), 120_000L, LocalDate.of(2024, 6, 1)))
                    .isEqualTo(5);
            assertThat(calc.usedCoef(LocalDate.of(2022, 1, 1), 120_000L, LocalDate.of(2024, 6, 1)))
                    .isZero();
        }

        @Test
        @DisplayName("год выпуска не заполнен -> 0 без NPE")
        void nullYearManufacture() {
            assertThat(calc.usedCoef(null, 200_000L, LocalDate.of(2024, 6, 1))).isZero();
        }

        @Test
        @DisplayName("пустое показание одометра трактуется как 0 км")
        void nullCounter() {
            when(dict.usedCoefRows()).thenReturn(List.of(used(8, 150_000, 10)));

            assertThat(calc.usedCoef(LocalDate.of(2000, 1, 1), null, LocalDate.of(2024, 6, 1)))
                    .isZero();
        }
    }

    // ---------------------------------------------------------- класс водителя

    @Nested
    @DisplayName("Коэффициент класса водителя")
    class DriveClasses {

        @Test
        @DisplayName("нормальный случай: класс найден в справочнике")
        void found() {
            when(dict.driveClasses()).thenReturn(List.of(new DriveClassRef("1", 25)));

            assertThat(calc.driveClassCoef("1")).isEqualTo(25);
        }

        @Test
        @DisplayName("отсутствие класса: null, пустой справочник, неизвестное имя -> 0")
        void missing() {
            assertThat(calc.driveClassCoef(null)).isZero();
            assertThat(calc.driveClassCoef("  ")).isZero();
            assertThat(calc.driveClassCoef("1")).isZero();

            when(dict.driveClasses()).thenReturn(List.of(new DriveClassRef("1", 25)));
            assertThat(calc.driveClassCoef("999")).isZero();
        }

        @Test
        @DisplayName("пустое поле coef даёт 0")
        void nullCoef() {
            when(dict.driveClasses()).thenReturn(List.of(new DriveClassRef("1", null)));

            assertThat(calc.driveClassCoef("1")).isZero();
        }
    }

    // ------------------------------------------------------------ сводные ветки

    @Nested
    @DisplayName("Сводный коэффициент")
    class Combined {

        private RouteCoefRef route() {
            return new RouteCoefRef(1L, 2L, 3L, 2, 1);
        }

        private void stubDictionaries() {
            when(dict.winterCoef(1L)).thenReturn(Optional.of(
                    winter(LocalDate.of(2000, 11, 1), LocalDate.of(2001, 3, 1), 5)));
            when(dict.mountainCoef(2L)).thenReturn(Optional.of(new SimpleCoefRef(2L, 10)));
            when(dict.cityCoef(3L)).thenReturn(Optional.of(new SimpleCoefRef(3L, 4)));
        }

        @Test
        @DisplayName("пассажирская ветка: колонки маршрута — значения, K = (5+2+2+3+0)-1 = 11")
        void passenger() {
            stubDictionaries();

            CoefficientBreakdown result = calc.passengerCoefficient(
                    route(), Y2020, 50_000L, LocalDate.of(2024, 12, 15));

            assertThat(result.winterCoef()).isEqualTo(5);
            assertThat(result.mountainCoef()).isEqualTo(2);
            assertThat(result.stationCoef()).isEqualTo(2);
            assertThat(result.cityCoef()).isEqualTo(3);
            assertThat(result.usedCoef()).isZero();
            assertThat(result.roadQuality()).isEqualTo(1);
            assertThat(result.k()).isEqualTo(11);
            assertThat(result.multiplier()).isCloseTo(1.11, within(1e-9));
        }

        @Test
        @DisplayName("настоящие значения из данных применяются, а не обнуляются поиском")
        void passengerUsesRealWorldValues() {
            RouteCoefRef route = new RouteCoefRef(null, 15L, 20L, null, null);

            CoefficientBreakdown result = calc.passengerCoefficient(
                    route, Y2020, 50_000L, LocalDate.of(2024, 6, 15));

            assertThat(result.mountainCoef()).isEqualTo(15);
            assertThat(result.cityCoef()).isEqualTo(20);
            assertThat(result.k()).isEqualTo(35);
            assertThat(result.multiplier()).isCloseTo(1.35, within(1e-9));
        }

        @Test
        @DisplayName("грузовая ветка: без station_coef и road_quality, K = 5+10+4 = 19")
        void cargo() {
            stubDictionaries();

            DirectionCoefRef direction = new DirectionCoefRef(1L, 2L, 3L);

            CoefficientBreakdown result = calc.cargoCoefficient(
                    direction, Y2020, 50_000L, LocalDate.of(2024, 12, 15));

            assertThat(result.stationCoef()).isZero();
            assertThat(result.roadQuality()).isZero();
            assertThat(result.k()).isEqualTo(19);
            assertThat(result.multiplier()).isCloseTo(1.19, within(1e-9));
        }

        @Test
        @DisplayName("K = 0 даёт множитель ровно 1 (а не 1 + 0)")
        void zeroK() {
            CoefficientBreakdown result = CoefficientBreakdown.of(0, 0, 0, 0, 0, 0);

            assertThat(result.k()).isZero();
            assertThat(result.multiplier()).isEqualTo(1d);
        }

        @Test
        @DisplayName("маршрут/направление не задано -> нейтральный множитель 1 без исключения")
        void missingRouteAndDirection() {
            assertThat(calc.passengerCoefficient(null, null, null, LocalDate.of(2024, 12, 15))
                    .multiplier()).isEqualTo(1d);
            assertThat(calc.passengerCoefficientLegacy(null, null, null, LocalDate.of(2024, 12, 15))
                    .multiplier()).isEqualTo(1d);
            assertThat(calc.cargoCoefficient(null, null, null, LocalDate.of(2024, 12, 15))
                    .multiplier()).isEqualTo(1d);
        }

        @Test
        @DisplayName("маршрут без коэффициентов: все поля null -> множитель 1")
        void routeWithoutCoefficients() {
            RouteCoefRef empty = new RouteCoefRef(null, null, null, null, null);

            CoefficientBreakdown result = calc.passengerCoefficient(
                    empty, null, null, LocalDate.of(2024, 12, 15));

            assertThat(result.k()).isZero();
            assertThat(result.multiplier()).isEqualTo(1d);
        }
    }
}
