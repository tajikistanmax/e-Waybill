package tj.mintrans.epd.waybill.calc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tj.mintrans.epd.waybill.calc.model.PassengerDay;
import tj.mintrans.epd.waybill.calc.model.PassengerMetrics;
import tj.mintrans.epd.waybill.calc.model.RoutePassengerRef;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Показатели пассажирской перевозки по многодневным ПЛ (формы 1-А, 3-С) —
 * портированы из rohkhat-v2 ({@code MultiDayPassengerMetricsServiceTest}).
 */
class MultiDayPassengerCalcTest {

    private static final LocalDate FROM = LocalDate.of(2024, 5, 1);
    private static final LocalDate TO = LocalDate.of(2024, 5, 31);

    private static RoutePassengerRef route() {
        return new RoutePassengerRef(10d, 14d, 0.5d, 6d, 4, 5d, 7d);
    }

    private static PassengerDay day(LocalDate date, Integer laps, String a, String b, Integer min) {
        return new PassengerDay(date, laps, a, b, null, null, LocalTime.of(6, 0), LocalTime.of(14, 0), min, null, null, null);
    }

    private static PassengerDay meterDay(LocalDate date, Long exit, Long entry, Integer min) {
        return new PassengerDay(date, 0, null, null, exit, entry, LocalTime.of(6, 0), LocalTime.of(14, 0), min, null, null, null);
    }

    private static List<PassengerDay> threeDays() {
        return List.of(
                day(LocalDate.of(2024, 5, 10), 3, "begin_path_a", "begin_path_b", 480),
                day(LocalDate.of(2024, 5, 11), 2, "begin_path_a", "begin_path_a", 240),
                day(LocalDate.of(2024, 6, 1), 7, "begin_path_a", "begin_path_b", 300));
    }

    private static List<PassengerDay> twoMeterDays() {
        return List.of(meterDay(LocalDate.of(2024, 5, 10), 1000L, 1120L, 480),
                meterDay(LocalDate.of(2024, 5, 11), 1120L, 1200L, 240));
    }

    @Nested
    @DisplayName("Форма 1-А (микроавтобус)")
    class Minibus {

        @Test
        @DisplayName("пассажирооборот = вместимость × коэф. × длина маршрута × Σ кругов за период")
        void turnover() {
            PassengerMetrics m = MultiDayPassengerCalc.forMinibus(route(), 40, threeDays(), FROM, TO, null);
            assertThat(m.passengerTurnover()).isEqualTo(1200d);
            assertThat(m.laps()).isEqualTo(5L);
            assertThat(m.workDays()).isEqualTo(2);
            assertThat(m.routeDistanceKm()).isEqualTo(60d);
        }

        @Test
        @DisplayName("число пассажиров = пассажирооборот ÷ средняя длина поездки")
        void passengerCount() {
            PassengerMetrics m = MultiDayPassengerCalc.forMinibus(route(), 40, threeDays(), FROM, TO, null);
            assertThat(m.passengerCount()).isEqualTo(200d);
        }

        @Test
        @DisplayName("нулевые пробеги суммируются по всем дням периода (в оригинале — [БАГ])")
        void zeroRunsSummed() {
            PassengerMetrics m = MultiDayPassengerCalc.forMinibus(route(), 40, threeDays(), FROM, TO, null);
            assertThat(m.totalDistanceKm()).isEqualTo(82d);   // 60 + (5+7) + (5+5)
            assertThat(m.totalDistanceKm()).isNotEqualTo(70d); // дефект оригинала
        }

        @Test
        @DisplayName("период — аргумент: дни вне периода не входят")
        void periodIsArgument() {
            List<PassengerDay> d = threeDays();
            PassengerMetrics may = MultiDayPassengerCalc.forMinibus(route(), 40, d, FROM, TO, null);
            PassengerMetrics june = MultiDayPassengerCalc.forMinibus(route(), 40, d,
                    LocalDate.of(2024, 6, 1), LocalDate.of(2024, 6, 30), null);
            assertThat(may.workDays()).isEqualTo(2);
            assertThat(may.laps()).isEqualTo(5L);
            assertThat(june.workDays()).isEqualTo(1);
            assertThat(june.laps()).isEqualTo(7L);
        }

        @Test
        @DisplayName("пустая средняя длина поездки не делит на ноль")
        void emptySeatLength() {
            RoutePassengerRef r = new RoutePassengerRef(10d, 14d, 0.5d, null, 4, 5d, 7d);
            PassengerMetrics m = MultiDayPassengerCalc.forMinibus(r, 40, threeDays(), FROM, TO, null);
            assertThat(m.passengerCount()).isEqualTo(1200d);
            assertThat(m.passengerCount()).isFinite();
        }

        @Test
        @DisplayName("неизвестный селектор нулевого пробега → 0")
        void unknownSelector() {
            PassengerDay d = day(LocalDate.of(2024, 5, 10), 3, "distance_a", "coe_use_capacity", 480);
            PassengerMetrics m = MultiDayPassengerCalc.forMinibus(route(), 40, List.of(d), FROM, TO, null);
            assertThat(m.totalDistanceKm()).isEqualTo(36d); // 12 * 3 + 0
        }

        @Test
        @DisplayName("план кругов = плановые круги маршрута × число рабочих дней периода")
        void plannedLaps() {
            PassengerMetrics m = MultiDayPassengerCalc.forMinibus(route(), 40, threeDays(), FROM, TO, null);
            assertThat(m.plannedLaps()).isEqualTo(8d);
        }

        @Test
        @DisplayName("день без даты пропускается")
        void dayWithoutDate() {
            List<PassengerDay> d = new ArrayList<>(threeDays());
            d.add(day(null, 100, "begin_path_a", "begin_path_b", 480));
            PassengerMetrics m = MultiDayPassengerCalc.forMinibus(route(), 40, d, FROM, TO, null);
            assertThat(m.workDays()).isEqualTo(2);
            assertThat(m.laps()).isEqualTo(5L);
        }

        @Test
        @DisplayName("лист без маршрута / без дней → нули, без исключения")
        void safeEmpty() {
            assertThatCode(() -> MultiDayPassengerCalc.forMinibus(null, 40, threeDays(), FROM, TO, null))
                    .doesNotThrowAnyException();
            assertThat(MultiDayPassengerCalc.forMinibus(null, 40, threeDays(), FROM, TO, null).workDays()).isZero();
            assertThat(MultiDayPassengerCalc.forMinibus(route(), 40, null, FROM, TO, null).laps()).isZero();
        }

        @Test
        @DisplayName("касса переносится, выручки у формы нет; время суммируется")
        void kassaAndTime() {
            PassengerMetrics m = MultiDayPassengerCalc.forMinibus(route(), 40, threeDays(), FROM, TO,
                    new BigDecimal("980.50"));
            assertThat(m.kassa()).isEqualByComparingTo("980.50");
            assertThat(m.earning()).isEqualByComparingTo("0");
            assertThat(m.workTimeMinutes()).isEqualTo(720);
            assertThat(m.speedometerBased()).isFalse();
        }

        @Test
        @DisplayName("время восполняется по выезду/возврату, если минут нет")
        void workTimeFallback() {
            PassengerDay d = new PassengerDay(LocalDate.of(2024, 5, 10), 1, "begin_path_a", "begin_path_b",
                    null, null, LocalTime.of(6, 0), LocalTime.of(14, 30), null, null, null, null);
            PassengerMetrics m = MultiDayPassengerCalc.forMinibus(route(), 40, List.of(d), FROM, TO, null);
            assertThat(m.workTimeMinutes()).isEqualTo(510);
        }
    }

    @Nested
    @DisplayName("Форма 3-С (такси)")
    class Taxi {

        @Test
        @DisplayName("тип 1 «свободное такси»: пробег по счётчику, 75 % с пассажиром, поездка 15 км")
        void meter() {
            PassengerMetrics m = MultiDayPassengerCalc.forTaxi((short) 1, route(), 40, 4, twoMeterDays(), FROM, TO, null);
            assertThat(m.totalDistanceKm()).isEqualTo(200d);
            assertThat(m.routeDistanceKm()).isEqualTo(150d);
            assertThat(m.passengerCount()).isEqualTo(20d);
            assertThat(m.passengerTurnover()).isEqualTo(300d);
            assertThat(m.speedometerBased()).isTrue();
        }

        @Test
        @DisplayName("тип 1: обратный ход одометра не даёт отрицательного пробега")
        void meterRollback() {
            PassengerDay d = meterDay(LocalDate.of(2024, 5, 10), 1200L, 1000L, 480);
            PassengerMetrics m = MultiDayPassengerCalc.forTaxi((short) 1, route(), 40, 4, List.of(d), FROM, TO, null);
            assertThat(m.totalDistanceKm()).isZero();
        }

        @Test
        @DisplayName("тип 2 «маршрутное такси»: та же формула, что у формы 1-А")
        void routeMatchesMinibus() {
            PassengerMetrics taxi = MultiDayPassengerCalc.forTaxi((short) 2, route(), 40, 4, threeDays(), FROM, TO, null);
            PassengerMetrics minibus = MultiDayPassengerCalc.forMinibus(route(), 40, threeDays(), FROM, TO, null);
            assertThat(taxi.passengerTurnover()).isEqualTo(minibus.passengerTurnover());
            assertThat(taxi.totalDistanceKm()).isEqualTo(minibus.totalDistanceKm());
            assertThat(taxi.laps()).isEqualTo(minibus.laps());
        }

        @Test
        @DisplayName("тип 3 «почасовая аренда»: 4 пассажира в час, поездка 15 км")
        void hourly() {
            PassengerMetrics m = MultiDayPassengerCalc.forTaxi((short) 3, route(), 40, 4, twoMeterDays(), FROM, TO, null);
            assertThat(m.passengerCount()).isEqualTo(48d);      // 12 ч × 4
            assertThat(m.passengerTurnover()).isEqualTo(720d);  // 48 × 15
            assertThat(m.totalDistanceKm()).isEqualTo(200d);
            assertThat(m.routeDistanceKm()).isZero();
            assertThat(m.laps()).isZero();
        }

        @Test
        @DisplayName("тип 3: день с нулевым показанием выезда не теряется")
        void hourlyZeroExit() {
            PassengerDay d = meterDay(LocalDate.of(2024, 5, 10), 0L, 60L, 480);
            PassengerMetrics m = MultiDayPassengerCalc.forTaxi((short) 3, route(), 40, 4, List.of(d), FROM, TO, null);
            assertThat(m.workDays()).isEqualTo(1);
            assertThat(m.totalDistanceKm()).isEqualTo(60d);
        }

        @Test
        @DisplayName("тип 1 и 3 не требуют маршрута; неизвестный тип → нули")
        void meterHourlyNoRouteAndUnknown() {
            assertThatCode(() -> MultiDayPassengerCalc.forTaxi((short) 1, null, 40, 4, twoMeterDays(), FROM, TO, null))
                    .doesNotThrowAnyException();
            assertThat(MultiDayPassengerCalc.forTaxi((short) 1, null, 40, 4, twoMeterDays(), FROM, TO, null)
                    .totalDistanceKm()).isEqualTo(200d);
            assertThat(MultiDayPassengerCalc.forTaxi((short) 9, route(), 40, 4, twoMeterDays(), FROM, TO, null)
                    .workDays()).isZero();
            assertThat(MultiDayPassengerCalc.forTaxi(null, route(), 40, 4, twoMeterDays(), FROM, TO, null)
                    .passengerTurnover()).isZero();
        }

        @Test
        @DisplayName("тип 1 берёт вместимость ТС, тип 2 — вместимость по марке")
        void capacitySource() {
            PassengerMetrics meter = MultiDayPassengerCalc.forTaxi((short) 1, route(), 40, 4, twoMeterDays(), FROM, TO, null);
            PassengerMetrics routed = MultiDayPassengerCalc.forTaxi((short) 2, route(), 40, 4, threeDays(), FROM, TO, null);
            assertThat(meter.passengerCount()).isEqualTo(20d);
            assertThat(routed.passengerTurnover()).isEqualTo(1200d);
        }

        @Test
        @DisplayName("тип 1 ОДНОДНЕВНЫЙ: один день из шапки ПЛ (как в WaybillCalcAssembler) — формула таксометра")
        void meterSingleDayFromHeader() {
            // Обвязка живого пути (WaybillCalcAssembler) для однодневного такси синтезирует один
            // PassengerDay из шапки ПЛ (одометр выезд/возврат) и зовёт forTaxi(1, null, cap, cap, [день]).
            // Эталон Calc.php::taxi_type: gashti_umumi=300; gasht_musofir=300·0.75=225;
            // miqdori=(225·(4/2))/15=30; gardishi=30·15=450.
            PassengerDay headerDay = new PassengerDay(
                    LocalDate.of(2024, 5, 10), null, null, null, 1000L, 1300L, null, null, null, null, null, null);
            PassengerMetrics m = MultiDayPassengerCalc.forTaxi((short) 1, null, 4, 4, List.of(headerDay), null, null, null);
            assertThat(m.totalDistanceKm()).isEqualTo(300d);   // gashti_umumi
            assertThat(m.routeDistanceKm()).isEqualTo(225d);   // gasht_musofir (75 %)
            assertThat(m.passengerCount()).isEqualTo(30d);     // miqdori
            assertThat(m.passengerTurnover()).isEqualTo(450d); // gardishi
            assertThat(m.speedometerBased()).isTrue();
        }
    }
}
