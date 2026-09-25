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
 * @param fuelDeviationLiters   Σ «фарқият» = норма − выдано, л (как в legacy «фаркият = меъёр − асл»:
 *                              экономия > 0, перерасход < 0; до 25.09.2026 знак был обратный)
 * @param revenue               Σ выручки
 * @param kassa                 Σ кассы
 * @param driverSalary          Σ заработка водителей
 * @param workDays              Σ рабочих дней (legacy «рӯзи корӣ»)
 * @param workHours             Σ отработанных часов (legacy «соат»)
 * @param transportWork         Σ транспортной работы P (грузооборот), т·км (legacy «гардиши бор»)
 * @param trips                 Σ ездок Z (legacy «рейсҳо» грузовых)
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
        BigDecimal driverSalary,
        int workDays,
        double workHours,
        double transportWork,
        double trips
) {

    public static ReportRow zero(String key, String label) {
        return new ReportRow(key, label, 0, 0L, 0d, 0d, 0d, 0d, 0d, 0d, 0d,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, 0d, 0d, 0d);
    }

    /** Прибавить показатели одного путевого листа (без грузовых величин). */
    public ReportRow plus(long addLaps, double addDistance, double addRouteDistance,
                          double addTurnover, double addPax, double addNorm, double addGiven,
                          BigDecimal addRevenue, BigDecimal addKassa, BigDecimal addSalary) {
        return plus(addLaps, addDistance, addRouteDistance, addTurnover, addPax, addNorm, addGiven,
                addRevenue, addKassa, addSalary, 0, 0d, 0d, 0d);
    }

    /** Прибавить показатели одного путевого листа, включая рабочие дни, часы и грузовые P / Z. */
    public ReportRow plus(long addLaps, double addDistance, double addRouteDistance,
                          double addTurnover, double addPax, double addNorm, double addGiven,
                          BigDecimal addRevenue, BigDecimal addKassa, BigDecimal addSalary,
                          int addWorkDays, double addWorkHours, double addTransportWork, double addTrips) {
        return new ReportRow(key, label,
                waybills + 1,
                laps + addLaps,
                distanceKm + addDistance,
                routeDistanceKm + addRouteDistance,
                passengerTurnover + addTurnover,
                passengerCount + addPax,
                fuelNormLiters + addNorm,
                fuelGivenLiters + addGiven,
                fuelDeviationLiters + (addNorm - addGiven),
                revenue.add(nz(addRevenue)),
                kassa.add(nz(addKassa)),
                driverSalary.add(nz(addSalary)),
                workDays + addWorkDays,
                workHours + addWorkHours,
                transportWork + addTransportWork,
                trips + addTrips);
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
                driverSalary.add(other.driverSalary),
                workDays + other.workDays,
                workHours + other.workHours,
                transportWork + other.transportWork,
                trips + other.trips);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
