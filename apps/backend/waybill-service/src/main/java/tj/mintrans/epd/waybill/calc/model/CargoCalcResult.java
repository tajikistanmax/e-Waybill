package tj.mintrans.epd.waybill.calc.model;

import java.util.List;

/**
 * Сводный результат расчёта грузового путевого листа (перенос {@code CargoFuelBase} +
 * {@code WaybillFuelService.calcCargo} из ИС «Роҳхат»).
 *
 * @param distanceKm      пробег = одометр возврата − выезда (≥ 0)
 * @param coefficients    разложение сводного коэффициента (грузовая ветка)
 * @param bodyType        тип кузова: LABADOR | SELF_UNLOAD | SPECIAL | SPECIAL_MOVER | NONE
 * @param hasTrailer      прицеп (5-я цифра кода марки > 0)
 * @param transportWork   транспортная работа P, т·км
 * @param trips           число ездок Z
 * @param fuels           расход и остатки по видам топлива
 * @param totalNormLiters суммарный нормативный расход, л
 * @param salary          заработок водителя
 */
public record CargoCalcResult(
        int distanceKm,
        CoefficientBreakdown coefficients,
        String bodyType,
        boolean hasTrailer,
        double transportWork,
        double trips,
        List<FuelConsumption> fuels,
        double totalNormLiters,
        DriverSalary salary
) {
    public CargoCalcResult {
        fuels = fuels == null ? List.of() : List.copyOf(fuels);
    }
}
