package tj.mintrans.epd.waybill.calc.report;

import java.math.BigDecimal;

/**
 * Строка отчёта — агрегат показателей по одному значению группировки (ТС, маршрут,
 * марка, водитель) либо по одному путевому листу.
 *
 * @param key                   ключ группировки (госномер / номер маршрута / имя марки / РМА водителя / № ПЛ)
 * @param label                 человекочитаемая подпись строки
 * @param waybills              число путевых листов в группе
 * @param laps                  Σ выполненных кругов (рейсов)
 * @param distanceKm            Σ общего пробега, км
 * @param routeDistanceKm       Σ пробега по маршруту (с пассажирами), км
 * @param passengerTurnover     Σ пассажирооборота, пасс-км
 * @param passengerCount        Σ перевезённых пассажиров
 * @param fuelNormLiters        Σ нормативного расхода топлива, л
 * @param fuelGivenLiters       Σ выданного топлива, л
 * @param fuelDeviationLiters   Σ отклонения «выдано − норма», л (перерасход > 0)
 * @param revenue               Σ выручки
 * @param kassa                 Σ кассы
 * @param driverSalary          Σ заработка водителей
 */
public record ReportRow(
        String key,
        String label,
        int waybills,
        long laps,
        double distanceKm,
        double routeDistanceKm,
        double passengerTurnover,
        double passengerCount,
        double fuelNormLiters,
        double fuelGivenLiters,
        double fuelDeviationLiters,
        BigDecimal revenue,
        BigDecimal kassa,
        BigDecimal driverSalary
) {

    public static ReportRow zero(String key, String label) {
        return new ReportRow(key, label, 0, 0L, 0d, 0d, 0d, 0d, 0d, 0d, 0d,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /** Прибавить показатели одного путевого листа. */
    public ReportRow plus(long addLaps, double addDistance, double addRouteDistance,
                          double addTurnover, double addPax, double addNorm, double addGiven,
                          BigDecimal addRevenue, BigDecimal addKassa, BigDecimal addSalary) {
        return new ReportRow(key, label,
                waybills + 1,
                laps + addLaps,
                distanceKm + addDistance,
                routeDistanceKm + addRouteDistance,
                passengerTurnover + addTurnover,
                passengerCount + addPax,
                fuelNormLiters + addNorm,
                fuelGivenLiters + addGiven,
                fuelDeviationLiters + (addGiven - addNorm),
                revenue.add(nz(addRevenue)),
                kassa.add(nz(addKassa)),
                driverSalary.add(nz(addSalary)));
    }

    /** Слить две строки (для строки «ИТОГО»). */
    public ReportRow merge(ReportRow other) {
        return new ReportRow(key, label,
                waybills + other.waybills,
                laps + other.laps,
                distanceKm + other.distanceKm,
                routeDistanceKm + other.routeDistanceKm,
                passengerTurnover + other.passengerTurnover,
                passengerCount + other.passengerCount,
                fuelNormLiters + other.fuelNormLiters,
                fuelGivenLiters + other.fuelGivenLiters,
                fuelDeviationLiters + other.fuelDeviationLiters,
                revenue.add(other.revenue),
                kassa.add(other.kassa),
                driverSalary.add(other.driverSalary));
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
