package tj.mintrans.epd.waybill.calc.model;

/**
 * Результат расчёта нормативного расхода топлива по одному виду топлива.
 *
 * <p>Эталон: docs/spec/07-calculations.md §1.5–§1.6,
 * {@code helpers.php:294, fuel_calc_100()} и {@code helpers.php:172, fuel_calc_100_day()}.</p>
 *
 * @param fuelId                вид топлива
 * @param normLiters            нормативный расход {@code Ma}, л (никогда не отрицательный)
 * @param normPer100Km          норма на 100 км с учётом коэффициентов и надбавок
 * @param conditionerLiters     надбавка на кондиционер, л
 * @param interiorHeatingLiters надбавка на отопление салона, л
 * @param specialWorkLiters     надбавка на спецработу, л
 */
public record FuelNormResult(
        long fuelId,
        double normLiters,
        double normPer100Km,
        double conditionerLiters,
        double interiorHeatingLiters,
        double specialWorkLiters
) {

    /**
     * Нулевой результат — вид топлива не выдавался либо пробег нулевой.
     *
     * @param fuelId вид топлива
     * @return нулевой результат
     */
    public static FuelNormResult zero(long fuelId) {
        return new FuelNormResult(fuelId, 0d, 0d, 0d, 0d, 0d);
    }
}
