package tj.mintrans.epd.waybill.calc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Последовательный расход двух видов топлива за рабочий день (месячные формы 1-А и 3-С).
 * Портировано из rohkhat-v2 ({@code SequentialFuelTest}).
 */
class SequentialFuelTest {

    private static final double PRECISION = 0.005;

    @Test
    @DisplayName("первому виду хватило на весь путь — второй не расходуется")
    void firstFuelCoversWholeDistance() {
        List<SequentialFuel.Line> lines = SequentialFuel.distribute(200d, List.of(
                new SequentialFuel.Line(1, 20d, 50d),
                new SequentialFuel.Line(2, 30d, 100d)));

        assertThat(lines.get(0).consumption()).isCloseTo(40d, within(PRECISION));
        assertThat(lines.get(1).consumption()).isCloseTo(0d, within(PRECISION));
    }

    @Test
    @DisplayName("первого не хватило — остаток пути идёт на второй")
    void secondFuelTakesRemainder() {
        List<SequentialFuel.Line> lines = SequentialFuel.distribute(200d, List.of(
                new SequentialFuel.Line(1, 20d, 30d),
                new SequentialFuel.Line(2, 30d, 100d)));

        assertThat(lines.get(0).consumption()).isCloseTo(30d, within(PRECISION));
        assertThat(lines.get(1).consumption()).isCloseTo(15d, within(PRECISION));
    }

    @Test
    @DisplayName("один вид топлива — весь путь на нём")
    void singleFuelTakesEverything() {
        List<SequentialFuel.Line> lines = SequentialFuel.distribute(200d, List.of(
                new SequentialFuel.Line(1, 20d, 10d)));

        assertThat(lines.get(0).consumption()).isCloseTo(40d, within(PRECISION));
    }

    @Test
    @DisplayName("пустой первый бак — весь путь на втором")
    void emptyFirstTank() {
        List<SequentialFuel.Line> lines = SequentialFuel.distribute(200d, List.of(
                new SequentialFuel.Line(1, 20d, 0d),
                new SequentialFuel.Line(2, 25d, 100d)));

        assertThat(lines.get(0).consumption()).isCloseTo(0d, within(PRECISION));
        assertThat(lines.get(1).consumption()).isCloseTo(50d, within(PRECISION));
    }

    @Test
    @DisplayName("нулевой пробег — топливо не расходуется")
    void zeroDistance() {
        List<SequentialFuel.Line> lines = SequentialFuel.distribute(0d, List.of(
                new SequentialFuel.Line(1, 20d, 50d),
                new SequentialFuel.Line(2, 30d, 100d)));

        assertThat(lines.get(0).consumption()).isCloseTo(0d, within(PRECISION));
        assertThat(lines.get(1).consumption()).isCloseTo(0d, within(PRECISION));
    }

    @Test
    @DisplayName("нулевая норма не делит на ноль")
    void zeroNormIsSafe() {
        List<SequentialFuel.Line> lines = SequentialFuel.distribute(100d, List.of(
                new SequentialFuel.Line(1, 0d, 50d),
                new SequentialFuel.Line(2, 25d, 100d)));

        assertThat(lines.get(0).consumption()).isCloseTo(0d, within(PRECISION));
        assertThat(lines.get(1).consumption()).isCloseTo(25d, within(PRECISION));
    }

    @Test
    @DisplayName("три вида топлива расходуются той же очередью")
    void thirdFuelContinuesTheChain() {
        List<SequentialFuel.Line> lines = SequentialFuel.distribute(300d, List.of(
                new SequentialFuel.Line(1, 20d, 20d),
                new SequentialFuel.Line(2, 30d, 30d),
                new SequentialFuel.Line(3, 40d, 100d)));

        assertThat(lines.get(0).consumption()).isCloseTo(20d, within(PRECISION));
        assertThat(lines.get(1).consumption()).isCloseTo(30d, within(PRECISION));
        assertThat(lines.get(2).consumption()).isCloseTo(40d, within(PRECISION));
    }
}
