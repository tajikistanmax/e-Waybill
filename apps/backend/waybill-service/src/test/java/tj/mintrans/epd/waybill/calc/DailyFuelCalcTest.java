package tj.mintrans.epd.waybill.calc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tj.mintrans.epd.waybill.calc.DailyFuelCalc.DayInput;
import tj.mintrans.epd.waybill.calc.DailyFuelCalc.DayLine;
import tj.mintrans.epd.waybill.calc.DailyFuelCalc.DayResult;
import tj.mintrans.epd.waybill.calc.model.FuelConsumption;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * MIGRATION.md §5.4 (B10) — посуточный расход топлива форм 1-А/3-С: числа по формулам legacy
 * {@code MBusTrait::calcFuel} (ручной расчёт по PHP-коду).
 */
class DailyFuelCalcTest {

    private static final LocalDate D1 = LocalDate.of(2026, 9, 1);
    private static final LocalDate D2 = LocalDate.of(2026, 9, 2);

    @Test
    @DisplayName("два бака за день: первый покрывает 100 км (расход 10 = выдано), остаток пути 20 км — второй (норма 8 → 1.6)")
    void twoTanksSequential() {
        // legacy: l_main=120; fuel1: fuel_fro_100=10, l_main_left = 120 − (10/10)·100 = 20 ≥ 0 и есть второй →
        //   consumption = (120−20)·10·0.01 = 10, remain = 10 − 10 = 0; fuel2: norm = 0.01·8·20 = 1.6, remain = 20 − 1.6 = 18.4
        DayInput day = new DayInput(D1, 120, 1.0, List.of(
                new DayLine(1, 10, 0, 0d), new DayLine(2, 20, 0, 0d)));
        List<DayResult> r = DailyFuelCalc.calculate(List.of(day), Map.of(1L, 10d, 2L, 8d));
        assertThat(r).hasSize(1);
        assertThat(r.getFirst().lines().get(0).consumption()).isCloseTo(10d, within(1e-9));
        assertThat(r.getFirst().lines().get(0).remainEntry()).isCloseTo(0d, within(1e-9));
        assertThat(r.getFirst().lines().get(1).consumption()).isCloseTo(1.6d, within(1e-9));
        assertThat(r.getFirst().lines().get(1).remainEntry()).isCloseTo(18.4d, within(1e-9));
    }

    @Test
    @DisplayName("один бак: весь пробег на него (l_main_left < 0) с коэффициентом дня 1.2; остаток цепочкой во второй день")
    void singleTankAndCarryOver() {
        // День 1: 120 км, норма 10 × 1.2 = 12 л/100 → расход 14.4; выдано 20 + остаток 5 → остаток 10.6.
        // День 2: 50 км, коэффициент 1 → норма 10 л/100; остаток до выезда не введён → берётся 10.6 (цепочка);
        // выдано 0 → расход 5, остаток 5.6.
        List<DayInput> days = List.of(
                new DayInput(D1, 120, 1.2, List.of(new DayLine(1, 20, 0, 5d))),
                new DayInput(D2, 50, 1.0, List.of(new DayLine(1, 0, 0, null))));
        List<DayResult> r = DailyFuelCalc.calculate(days, Map.of(1L, 10d));
        assertThat(r.get(0).lines().getFirst().consumption()).isCloseTo(14.4d, within(1e-9));
        assertThat(r.get(0).lines().getFirst().remainEntry()).isCloseTo(10.6d, within(1e-9));
        assertThat(r.get(1).lines().getFirst().remainBeforeExit()).isCloseTo(10.6d, within(1e-9));
        assertThat(r.get(1).lines().getFirst().consumption()).isCloseTo(5d, within(1e-9));
        assertThat(r.get(1).lines().getFirst().remainEntry()).isCloseTo(5.6d, within(1e-9));

        List<FuelConsumption> agg = DailyFuelCalc.aggregate(r);
        assertThat(agg).hasSize(1);
        FuelConsumption f = agg.getFirst();
        assertThat(f.given()).isEqualTo(20d);
        assertThat(f.normLiters()).isCloseTo(19.4d, within(1e-9));       // 14.4 + 5
        assertThat(f.remainBeforeExit()).isEqualTo(5d);                     // первого дня
        assertThat(f.remainEntry()).isCloseTo(5.6d, within(1e-9));          // последнего дня
        assertThat(DailyFuelCalc.totalNorm(agg)).isCloseTo(19.4d, within(1e-9));
    }

    @Test
    @DisplayName("вид топлива без норматива у марки — расход 0 (legacy подменял норму на 1 л/100 км — баг не переносится)")
    void unknownFuelNoNorm() {
        DayInput day = new DayInput(D1, 100, 1.0, List.of(new DayLine(3, 30, 0, 0d)));
        List<DayResult> r = DailyFuelCalc.calculate(List.of(day), Map.of(1L, 10d));
        assertThat(r.getFirst().lines().getFirst().consumption()).isZero();
        assertThat(r.getFirst().lines().getFirst().remainEntry()).isEqualTo(30d);
    }
}
