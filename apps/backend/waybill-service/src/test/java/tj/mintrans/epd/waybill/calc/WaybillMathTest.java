package tj.mintrans.epd.waybill.calc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tj.mintrans.epd.waybill.calc.model.DriverSalary;

import java.math.BigDecimal;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тесты сводных величин путевого листа: пробег, рабочее время, заработок водителя
 * (§2.1, §2.6 спецификации «Роҳхат»). Портированы из rohkhat-v2 ({@code WaybillCalculationServiceTest}).
 */
class WaybillMathTest {

    @Nested
    @DisplayName("Пробег по показаниям одометра")
    class Distance {

        @Test
        @DisplayName("нормальный случай: entry - exit")
        void normal() {
            assertThat(WaybillMath.distance(100_200L, 100_000L)).isEqualTo(200);
            assertThat(WaybillMath.distanceNonNegative(100_200L, 100_000L)).isEqualTo(200);
        }

        @Test
        @DisplayName("граничный случай: нулевой пробег")
        void zero() {
            assertThat(WaybillMath.distance(100_000L, 100_000L)).isZero();
            assertThat(WaybillMath.distanceNonNegative(100_000L, 100_000L)).isZero();
        }

        @Test
        @DisplayName("отрицательная разница обнуляется только в distanceNonNegative")
        void negative() {
            assertThat(WaybillMath.distance(900L, 1000L)).isEqualTo(-100);
            assertThat(WaybillMath.distanceNonNegative(900L, 1000L)).isZero();
        }

        @Test
        @DisplayName("пустые показания трактуются как 0, а не NPE")
        void nulls() {
            assertThat(WaybillMath.distance(null, 900L)).isEqualTo(-900);
            assertThat(WaybillMath.distance(900L, null)).isEqualTo(900);
            assertThat(WaybillMath.distanceNonNegative(null, null)).isZero();
        }

        @Test
        @DisplayName("недостоверные показания (заглушки) обнуляются")
        void implausible() {
            assertThat(WaybillMath.distance(4_294_967_295L, 0L)).isZero();
        }
    }

    @Nested
    @DisplayName("Рабочее время")
    class WorkTime {

        @Test
        @DisplayName("нормальный случай: разница между выездом и возвратом")
        void normal() {
            assertThat(WaybillMath.workTimeMinutes(LocalTime.of(6, 0), LocalTime.of(14, 30))).isEqualTo(510);
        }

        @Test
        @DisplayName("граничный случай: пустое время и нулевая разница")
        void edgeCases() {
            assertThat(WaybillMath.workTimeMinutes(null, LocalTime.of(14, 30))).isZero();
            assertThat(WaybillMath.workTimeMinutes(LocalTime.of(6, 0), null)).isZero();
            assertThat(WaybillMath.workTimeMinutes(LocalTime.of(6, 0), LocalTime.of(6, 0))).isZero();
        }

        @Test
        @DisplayName("обратный порядок дат даёт модуль разницы")
        void reversed() {
            assertThat(WaybillMath.workTimeMinutes(LocalTime.of(14, 30), LocalTime.of(6, 0))).isEqualTo(510);
        }
    }

    @Nested
    @DisplayName("Заработок водителя")
    class Salary {

        @Test
        @DisplayName("нормальный случай: ((1000/4)*3)*0.5 + 100 = 475.00")
        void normal() {
            DriverSalary salary = WaybillMath.driverSalary(new BigDecimal("1000"), 0.5d, (short) 100);

            assertThat(salary.payableBase()).isEqualByComparingTo("750.00");
            assertThat(salary.salary()).isEqualByComparingTo("475.00");
            assertThat(salary.salary().scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("нулевая выручка -> остаётся только надбавка за класс")
        void zeroEarning() {
            DriverSalary salary = WaybillMath.driverSalary(BigDecimal.ZERO, 0.5d, (short) 100);

            assertThat(salary.payableBase()).isEqualByComparingTo("0.00");
            assertThat(salary.salary()).isEqualByComparingTo("100.00");
        }

        @Test
        @DisplayName("отсутствие данных: null-выручка, null-процент и null-надбавка дают 0.00")
        void nullData() {
            DriverSalary salary = WaybillMath.driverSalary(null, null, null);

            assertThat(salary.earning()).isEqualByComparingTo("0.00");
            assertThat(salary.salary()).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("доля в процентах (30 вместо 0,3) трактуется как 30 %, а не как 30-кратная (находка 23.09)")
        void percentGivenAsPercent() {
            DriverSalary asPercent = WaybillMath.driverSalary(new BigDecimal("1250.50"), 30d, (short) 20);
            DriverSalary asShare = WaybillMath.driverSalary(new BigDecimal("1250.50"), 0.3d, (short) 20);

            // (1250.50/4)*3 = 937.875 ; * 0.3 = 281.3625 ; + 20 = 301.36
            assertThat(asPercent.salary()).isEqualByComparingTo("301.36");
            assertThat(asShare.salary()).isEqualByComparingTo(asPercent.salary());
            // заведомо ошибочная доля (> 100) — без начисления по доле, только надбавка
            assertThat(WaybillMath.driverSalary(new BigDecimal("1000"), 2132d, (short) 20).salary())
                    .isEqualByComparingTo("20.00");
            // граница: ровно 1 — это 100 %
            assertThat(WaybillMath.driverSalary(new BigDecimal("1000"), 1d, (short) 0).salary())
                    .isEqualByComparingTo("750.00");
        }

        @Test
        @DisplayName("округление HALF_UP до 2 знаков")
        void rounding() {
            DriverSalary salary = WaybillMath.driverSalary(new BigDecimal("100"), 0.3333d, (short) 0);

            // (100/4)*3 = 75 ; 75 * 0.3333 = 24.9975 -> 25.00
            assertThat(salary.salary()).isEqualByComparingTo("25.00");
        }

        @Test
        @DisplayName("надбавка за класс: cat_1/cat_2/cat_3, неизвестный класс -> 0")
        void classBonus() {
            assertThat(WaybillMath.classBonus((short) 100, (short) 200, (short) 300, 1)).isEqualTo((short) 100);
            assertThat(WaybillMath.classBonus((short) 100, (short) 200, (short) 300, 2)).isEqualTo((short) 200);
            assertThat(WaybillMath.classBonus((short) 100, (short) 200, (short) 300, 3)).isEqualTo((short) 300);
            assertThat(WaybillMath.classBonus((short) 100, (short) 200, (short) 300, 4)).isEqualTo((short) 0);
            assertThat(WaybillMath.classBonus((short) 100, (short) 200, (short) 300, null)).isEqualTo((short) 0);
            assertThat(WaybillMath.classBonus(null, null, null, 1)).isEqualTo((short) 0);
        }
    }
}
