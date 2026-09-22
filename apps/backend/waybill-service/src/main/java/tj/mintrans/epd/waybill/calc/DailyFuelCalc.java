package tj.mintrans.epd.waybill.calc;

import tj.mintrans.epd.waybill.calc.model.FuelConsumption;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Посуточный расход топлива многодневных пассажирских ПЛ форм 1-А и 3-С (MIGRATION.md 5.4, B10) —
 * перенос {@code app/Traits/MBusTrait.php::calcFuel} / {@code app/Traits/kvd/Bill3c1aTrait.php::calcFuel}.
 *
 * <p>По каждому рабочему дню: пробег дня {@code l_main} = одометр возврата − одометр выезда; норма на 100 км
 * = базовый норматив марки × коэффициент дня; топливо расходуется по строкам дня ПОСЛЕДОВАТЕЛЬНО
 * ({@link SequentialFuel}: первый бак покрывает, сколько может, остаток пути — следующий); остаток при
 * возврате = (выдано + довыдано + остаток до выезда) − расход.</p>
 *
 * <p>Отличия от legacy (осознанно, баги не переносятся): вид топлива без норматива у марки — расход 0
 * (в legacy норма подменялась на 1 л/100 км); довыдача учитывается и при нулевой выдаче (в legacy
 * {@code fuel_given ? given + additional : 0}). Улучшение: если остаток до выезда дня не введён, он берётся
 * из остатка при возврате предыдущего дня того же вида топлива (в legacy это делал префилл
 * {@code parking_fuel_give_multi_days}).</p>
 */
public final class DailyFuelCalc {

    private DailyFuelCalc() {
    }

    /** Строка топлива дня (как ввёл диспетчер). {@code remainBeforeExit == null} → цепочка от предыдущего дня. */
    public record DayLine(long fuelId, double given, double additional, Double remainBeforeExit) {
    }

    /** Рабочий день: дата, пробег и множитель коэффициента дня (1 — без маршрута / ветка Душанбе). */
    public record DayInput(LocalDate date, long distanceKm, double multiplier, List<DayLine> lines) {
    }

    public record LineResult(long fuelId, double norm100, double given, double additional, double remainBeforeExit,
                             double available, double consumption, double remainEntry) {
    }

    public record DayResult(LocalDate date, long distanceKm, double multiplier, List<LineResult> lines) {
    }

    /**
     * @param days          рабочие дни в хронологическом порядке
     * @param baseNorm100   базовый норматив марки по видам топлива, л/100 км (fuel_100 / fuel_100_dushanbe)
     */
    public static List<DayResult> calculate(List<DayInput> days, Map<Long, Double> baseNorm100) {
        List<DayResult> out = new ArrayList<>();
        Map<Long, Double> carry = new HashMap<>();   // остаток при возврате предыдущего дня по виду топлива
        for (DayInput day : days) {
            List<SequentialFuel.Line> lines = new ArrayList<>();
            List<double[]> parts = new ArrayList<>();   // given, additional, remainBefore — для результата
            for (DayLine l : day.lines()) {
                double base = baseNorm100 == null ? 0d : baseNorm100.getOrDefault(l.fuelId(), 0d);
                double norm100 = base * day.multiplier();
                double remainBefore = l.remainBeforeExit() != null ? l.remainBeforeExit() : carry.getOrDefault(l.fuelId(), 0d);
                double available = l.given() + l.additional() + remainBefore;
                lines.add(new SequentialFuel.Line((int) l.fuelId(), norm100, available));
                parts.add(new double[]{l.given(), l.additional(), remainBefore});
            }
            List<SequentialFuel.Line> distributed = SequentialFuel.distribute(day.distanceKm(), lines);
            List<LineResult> results = new ArrayList<>();
            for (int i = 0; i < distributed.size(); i++) {
                SequentialFuel.Line d = distributed.get(i);
                double[] p = parts.get(i);
                double consumption = CalcUtils.round(d.consumption(), CalcUtils.FUEL_SCALE);
                double remainEntry = CalcUtils.round(d.available() - consumption, CalcUtils.FUEL_SCALE);
                results.add(new LineResult(d.fuelId(), d.norm100(), p[0], p[1], p[2], d.available(), consumption, remainEntry));
                carry.put((long) d.fuelId(), remainEntry);
            }
            out.add(new DayResult(day.date(), day.distanceKm(), day.multiplier(), results));
        }
        return out;
    }

    /**
     * Свод по видам топлива для {@code PassengerCalcResult.fuels}: выдано/довыдано/норма — суммы по дням,
     * остаток до выезда — первого дня, остаток при возврате — последнего дня.
     */
    public static List<FuelConsumption> aggregate(List<DayResult> days) {
        Map<Long, double[]> acc = new LinkedHashMap<>();   // given, additional, norm, remainBeforeFirst, remainEntryLast
        for (DayResult day : days) {
            for (LineResult l : day.lines()) {
                double[] a = acc.computeIfAbsent(l.fuelId(), k -> new double[]{0, 0, 0, l.remainBeforeExit(), 0});
                a[0] += l.given();
                a[1] += l.additional();
                a[2] += l.consumption();
                a[4] = l.remainEntry();
            }
        }
        List<FuelConsumption> out = new ArrayList<>();
        acc.forEach((fuelId, a) -> out.add(new FuelConsumption(fuelId, a[0], a[1],
                CalcUtils.round(a[2], CalcUtils.FUEL_SCALE), a[3], a[4])));
        return out;
    }

    public static double totalNorm(List<FuelConsumption> fuels) {
        double sum = 0d;
        for (FuelConsumption f : fuels) {
            sum += f.normLiters();
        }
        return CalcUtils.round(sum, CalcUtils.FUEL_SCALE);
    }
}
