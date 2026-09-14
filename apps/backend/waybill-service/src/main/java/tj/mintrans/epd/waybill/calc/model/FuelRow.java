package tj.mintrans.epd.waybill.calc.model;

/**
 * Строка топлива путевого листа — одна запись JSON-поля {@code fuels} (или {@code fuel})
 * путевого листа либо рабочего дня.
 *
 * <p>Эталон: docs/spec/07-calculations.md §1.3, {@code helpers.php:60, fuel_calc()}.</p>
 *
 * @param fuelId               вид топлива: 1 = бензин, 2 = дизель, 3 = газ
 * @param fuelGiven            выдано топлива, л
 * @param coefBelow0           надбавка «при температуре ниже 0», л
 * @param additional           дозаправка в пути, л
 * @param remainFuelBeforeExit остаток в баке до выезда, л
 * @param remainFuelEntry      остаток в баке при возврате, л
 * @param consumption          фактический расход, л (заполняется расчётом)
 */
public record FuelRow(
        long fuelId,
        double fuelGiven,
        double coefBelow0,
        double additional,
        double remainFuelBeforeExit,
        double remainFuelEntry,
        double consumption
) {

    /**
     * Минимальная строка: вид топлива и выданное количество.
     *
     * @param fuelId    вид топлива
     * @param fuelGiven выдано, л
     * @return строка с нулевыми прочими полями
     */
    public static FuelRow ofGiven(long fuelId, double fuelGiven) {
        return new FuelRow(fuelId, fuelGiven, 0d, 0d, 0d, 0d, 0d);
    }
}
