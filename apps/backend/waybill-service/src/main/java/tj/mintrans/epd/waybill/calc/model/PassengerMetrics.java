package tj.mintrans.epd.waybill.calc.model;

import java.math.BigDecimal;

/**
 * Показатели перевозки по одному пассажирскому путевому листу
 * (перенос {@code PassengerMetrics} из ИС «Роҳхат», §2.1–§2.3). Основа отчётов
 * раздела «Пассажирские перевозки».
 *
 * @param workDays          учтённых рабочих дней
 * @param laps              выполнено кругов (рейсов)
 * @param plannedLaps       запланировано кругов ({@code route.planned_lap})
 * @param passengerTurnover пассажирооборот, пасс-км
 * @param passengerCount    перевезено пассажиров
 * @param routeDistanceKm   пробег по маршруту, км (без нулевых пробегов)
 * @param totalDistanceKm   общий пробег, км
 * @param workTimeMinutes   отработанное время, мин
 * @param earning           выручка
 * @param kassa             касса
 * @param speedometerBased  общий пробег взят по спидометру (форма 1-АД г. Душанбе)
 */
public record PassengerMetrics(
        int workDays,
        long laps,
        double plannedLaps,
        double passengerTurnover,
        double passengerCount,
        double routeDistanceKm,
        double totalDistanceKm,
        int workTimeMinutes,
        BigDecimal earning,
        BigDecimal kassa,
        boolean speedometerBased
) {

    /** Нулевые показатели — лист без маршрута. */
    public static PassengerMetrics empty() {
        return new PassengerMetrics(0, 0L, 0d, 0d, 0d, 0d, 0d, 0,
                BigDecimal.ZERO, BigDecimal.ZERO, false);
    }
}
