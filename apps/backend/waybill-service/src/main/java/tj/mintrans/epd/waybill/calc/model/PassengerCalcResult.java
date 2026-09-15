package tj.mintrans.epd.waybill.calc.model;

import java.util.List;

/**
 * Сводный результат расчёта пассажирского путевого листа
 * (перенос {@code WaybillCalculationResult} + {@code PassengerMetrics} + тариф).
 *
 * @param distanceKm       пробег = одометр возврата − выезда (≥ 0)
 * @param workTimeMinutes  рабочее время, мин
 * @param workHours        рабочее время, ч
 * @param coefficients     разложение сводного коэффициента
 * @param fuels            расход и остатки по видам топлива
 * @param totalNormLiters  суммарный нормативный расход, л
 * @param salary           заработок водителя
 * @param passengerMetrics пассажирооборот, пассажиры, пробег по маршруту
 * @param tariff           стоимость по тарифу маршрута (нархнома)
 */
public record PassengerCalcResult(
        int distanceKm,
        int workTimeMinutes,
        double workHours,
        CoefficientBreakdown coefficients,
        List<FuelConsumption> fuels,
        double totalNormLiters,
        DriverSalary salary,
        PassengerMetrics passengerMetrics,
        TariffAmount tariff
) {
    public PassengerCalcResult {
        fuels = fuels == null ? List.of() : List.copyOf(fuels);
    }
}
