package tj.mintrans.epd.waybill.calc.model;

/**
 * Сводка по одному виду топлива в путевом листе: выдано, норматив, остатки
 * (перенос {@code FuelConsumption} из ИС «Роҳхат», §2.1/§3.5).
 *
 * @param fuelId           вид топлива
 * @param given            выдано, л (с надбавкой coef_below_0)
 * @param additional       дозаправка в пути, л
 * @param normLiters       нормативный расход Ma, л
 * @param remainBeforeExit остаток до выезда, л
 * @param remainEntry      остаток при возврате, л = before + given + additional − norm
 */
public record FuelConsumption(
        long fuelId,
        double given,
        double additional,
        double normLiters,
        double remainBeforeExit,
        double remainEntry
) {
}
