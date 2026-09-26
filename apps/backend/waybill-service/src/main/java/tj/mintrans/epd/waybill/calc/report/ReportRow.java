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
 * @param fuelNormPetrol        норма по видам топлива legacy «меъёр Б/С/Г» (сверка 25.09, D6): бензин (1),
 * @param fuelNormDiesel        дизель/солярка (2),
 * @param fuelNormGas           газ сжиженный и природный (3, 4); электроэнергия троллейбуса в Б/С/Г не входит
 * @param fuelGivenPetrol       выдано по видам legacy «асл Б/С/Г»; фарқият по виду = норма − выдано
 * @param fuelGivenDiesel       …
 * @param fuelGivenGas          …
 * @param detail                реквизиты одного листа (legacy типы 6 / 7 / 9 — построчно; сверка 25.09, D5):
 *                              у строки «по листу» — этот лист, у «Сузишвори» — последний лист ТС; иначе {@code null}
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
        double trips,
        double fuelNormPetrol,
        double fuelNormDiesel,
        double fuelNormGas,
        double fuelGivenPetrol,
        double fuelGivenDiesel,
        double fuelGivenGas,
        Detail detail
) {

    /** Норма и выдано по видам топлива Б/С/Г одного листа. */
    public record FuelSplit(double normPetrol, double normDiesel, double normGas,
                            double givenPetrol, double givenDiesel, double givenGas) {
        public static final FuelSplit ZERO = new FuelSplit(0, 0, 0, 0, 0, 0);
    }

    /**
     * Реквизиты одного путевого листа для построчных отчётов legacy (сверка 25.09, D5): тип 6 «Маълумот оид ба
     * гашт» (№, ТС, одометр), тип 7 «Дафтари қайди в/н» (+ водитель, табель, маршрут, выезд/возврат), тип 9
     * «Сузишвори» (топливо последнего листа ТС: выдано, остаток до выезда, норма, остаток при возврате).
     */
    public record Detail(String number, java.time.OffsetDateTime createdAt, String vehicle, String garageNumber,
                         String driverName, String driverTab, String route, String exitAt, String entryAt,
                         Integer odometerExit, Integer odometerEntry, Integer odometerDiff,
                         String fuelTypes, double fuelGiven, double fuelRemainBeforeExit, double fuelNorm,
                         double fuelRemainEntry) {
    }

    /** Та же строка с реквизитами листа. */
    public ReportRow withDetail(Detail d) {
        return new ReportRow(key, label, waybills, laps, distanceKm, routeDistanceKm, passengerTurnover,
                passengerCount, fuelNormLiters, fuelGivenLiters, fuelDeviationLiters, revenue, kassa, driverSalary,
                workDays, workHours, transportWork, trips, fuelNormPetrol, fuelNormDiesel, fuelNormGas,
                fuelGivenPetrol, fuelGivenDiesel, fuelGivenGas, d);
    }

    public static ReportRow zero(String key, String label) {
        return new ReportRow(key, label, 0, 0L, 0d, 0d, 0d, 0d, 0d, 0d, 0d,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, 0d, 0d, 0d, 0d, 0d, 0d, 0d, 0d, 0d, null);
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
        return plus(addLaps, addDistance, addRouteDistance, addTurnover, addPax, addNorm, addGiven,
                addRevenue, addKassa, addSalary, addWorkDays, addWorkHours, addTransportWork, addTrips, FuelSplit.ZERO);
    }

    /** То же с нормой и выданным по видам топлива Б/С/Г (сверка 25.09, D6). */
    public ReportRow plus(long addLaps, double addDistance, double addRouteDistance,
                          double addTurnover, double addPax, double addNorm, double addGiven,
                          BigDecimal addRevenue, BigDecimal addKassa, BigDecimal addSalary,
                          int addWorkDays, double addWorkHours, double addTransportWork, double addTrips,
                          FuelSplit f) {
        FuelSplit s = f == null ? FuelSplit.ZERO : f;
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
                trips + addTrips,
                fuelNormPetrol + s.normPetrol(),
                fuelNormDiesel + s.normDiesel(),
                fuelNormGas + s.normGas(),
                fuelGivenPetrol + s.givenPetrol(),
                fuelGivenDiesel + s.givenDiesel(),
                fuelGivenGas + s.givenGas(),
                detail);
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
                trips + other.trips,
                fuelNormPetrol + other.fuelNormPetrol,
                fuelNormDiesel + other.fuelNormDiesel,
                fuelNormGas + other.fuelNormGas,
                fuelGivenPetrol + other.fuelGivenPetrol,
                fuelGivenDiesel + other.fuelGivenDiesel,
                fuelGivenGas + other.fuelGivenGas,
                null);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
