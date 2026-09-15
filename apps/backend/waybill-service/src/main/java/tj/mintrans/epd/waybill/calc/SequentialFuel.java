package tj.mintrans.epd.waybill.calc;

import java.util.ArrayList;
import java.util.List;

/**
 * Последовательный расход топлива за рабочий день.
 *
 * <p>Правило месячных форм 1-А и 3-С: баки расходуются по очереди, а не
 * каждый на весь путь. Первый вид покрывает столько километров, на сколько
 * хватает его объёма, остаток пути ложится на второй, и так далее.</p>
 *
 * <p>Эталон: {@code app/Traits/MBusTrait.php}, {@code app/Traits/kvd/Bill3c1aTrait.php}:</p>
 * <pre>
 * $l_main_left = $l_main - (($fuel_given + $remain_fuel_before_exit) / $fuel_fro_100) * 100;
 * $suz_istifoda_shuda = $l_main_left &lt; 0 || !isset($fuels_for_day[1])
 *     ? $l_main * $fuel_fro_100 * 0.01
 *     : ($l_main - $l_main_left) * $fuel_fro_100 * 0.01;
 * </pre>
 *
 * <p>Портировано из rohkhat-v2 ({@code tj.etrans.rohkhat.calc.SequentialFuel}).</p>
 */
public final class SequentialFuel {

    private SequentialFuel() {
    }

    /**
     * Строка топлива рабочего дня.
     *
     * @param fuelId      вид топлива
     * @param norm100     норма расхода на 100 км с уже применёнными коэффициентами
     * @param available   объём в баке: выдано + дополнительно + остаток до выезда
     * @param consumption расход, рассчитанный распределением
     */
    public record Line(int fuelId, double norm100, double available, double consumption) {

        /** Строка до расчёта. */
        public Line(int fuelId, double norm100, double available) {
            this(fuelId, norm100, available, 0d);
        }

        Line withConsumption(double value) {
            return new Line(fuelId, norm100, available, value);
        }
    }

    /**
     * Распределение пробега по бакам в порядке их следования.
     *
     * @param distanceKm пробег за день, км
     * @param lines      строки топлива в том порядке, в котором их ввёл диспетчер
     * @return те же строки с рассчитанным расходом
     */
    public static List<Line> distribute(double distanceKm, List<Line> lines) {
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }
        List<Line> result = new ArrayList<>(lines.size());
        double left = Math.max(distanceKm, 0d);

        for (int index = 0; index < lines.size(); index++) {
            Line line = lines.get(index);
            boolean last = index == lines.size() - 1;

            if (line.norm100() <= 0) {
                // Норматив марки не задан. Делить на ноль нельзя, а списывать
                // по нулевой норме нечего: путь достаётся следующему баку.
                result.add(line.withConsumption(0d));
                continue;
            }
            if (left <= 0) {
                result.add(line.withConsumption(0d));
                continue;
            }

            double coveredByTank = line.available() / line.norm100() * 100d;
            double covered = last ? left : Math.min(left, Math.max(coveredByTank, 0d));
            double consumption = covered * line.norm100() * 0.01;

            result.add(line.withConsumption(consumption));
            left -= covered;
        }
        return result;
    }
}
