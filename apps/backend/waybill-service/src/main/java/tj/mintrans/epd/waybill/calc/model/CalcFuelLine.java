package tj.mintrans.epd.waybill.calc.model;

/**
 * Строка топлива на входе расчёта путевого листа.
 *
 * @param fuelId               вид топлива (1 бензин / 2 дизель / 3 газ)
 * @param fuelGiven            выдано, л
 * @param coefBelow0           надбавка «при температуре ниже 0», л
 * @param additional           дозаправка в пути, л
 * @param remainFuelBeforeExit остаток в баке до выезда, л
 */
public record CalcFuelLine(
        long fuelId,
        double fuelGiven,
        double coefBelow0,
        double additional,
        double remainFuelBeforeExit
) {
    /** Минимальная строка: вид топлива и выданное количество. */
    public static CalcFuelLine of(long fuelId, double fuelGiven) {
        return new CalcFuelLine(fuelId, fuelGiven, 0d, 0d, 0d);
    }
}
