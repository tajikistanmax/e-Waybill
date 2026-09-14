package tj.mintrans.epd.waybill.calc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tj.mintrans.epd.waybill.calc.model.PassengerDay;
import tj.mintrans.epd.waybill.calc.model.PassengerMetrics;
import tj.mintrans.epd.waybill.calc.model.RoutePassengerRef;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Показатели пассажирской перевозки по МНОГОДНЕВНЫМ путевым листам — формы 1-А
 * (микроавтобус) и 3-С (легковое такси). Перенос
 * {@code tj.etrans.rohkhat.calc.MultiDayPassengerMetricsService}
 * (docs/spec/07-calculations.md §2.2, §2.3).
 *
 * <p>Показатели считаются по рабочим дням, попавшим в период отчёта: лист формы 1-А/3-С
 * выписывается на срок до месяца, поэтому один лист попадает в отчёты разных периодов
 * разными днями. Период — аргумент ({@code from}/{@code to}), а не HTTP-сессия.</p>
 *
 * <p>Расхождения оригинала исправлены (как в rohkhat-v2): нулевые пробеги суммируются
 * (в оригинале — присваивание), делитель средней дальности защищён от нуля, значение
 * колонки-селектора проверяется по белому списку, день без даты пропускается.</p>
 */
public final class MultiDayPassengerCalc {

    private static final Logger log = LoggerFactory.getLogger(MultiDayPassengerCalc.class);

    private static final String PATH_A = "begin_path_a";
    private static final String PATH_B = "begin_path_b";

    /** Доля пробега с пассажиром для «свободного» такси (§2.3). */
    public static final double TAXI_PAID_RUN_SHARE = 0.75d;
    /** Средняя дальность поездки пассажира такси, км (§2.3). */
    public static final double TAXI_AVERAGE_TRIP_KM = 15d;
    /** Норматив числа пассажиров в час при почасовой аренде (§2.3). */
    public static final double HOURLY_PASSENGERS_PER_HOUR = 4d;

    private MultiDayPassengerCalc() {
    }

    /** Тип обслуживания формы 3-С (колонка {@code type_service}). */
    public enum TaxiServiceType {
        /** 1 — «свободное» такси: пробег по счётчику. */
        METER(1),
        /** 2 — маршрутное такси: пробег по маршруту и числу кругов. */
        ROUTE(2),
        /** 3 — почасовая аренда: пассажиры по отработанному времени. */
        HOURLY(3);

        private final int code;

        TaxiServiceType(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }

        public static TaxiServiceType byCode(Short code) {
            if (code == null) {
                return null;
            }
            for (TaxiServiceType t : values()) {
                if (t.code == code) {
                    return t;
                }
            }
            return null;
        }
    }

    /**
     * Показатели по многодневному листу формы 1-А.
     *
     * @param route    путевые поля маршрута ({@code null} → нулевые показатели)
     * @param capacity вместимость ТС по марке, мест
     * @param days     рабочие дни листа
     * @param from     начало периода включительно ({@code null} — без нижней границы)
     * @param to       конец периода включительно ({@code null} — без верхней границы)
     * @param kassa    касса листа
     * @return показатели перевозки за период
     */
    public static PassengerMetrics forMinibus(RoutePassengerRef route, Integer capacity,
                                              List<PassengerDay> days, LocalDate from, LocalDate to,
                                              BigDecimal kassa) {
        if (route == null) {
            log.warn("Многодневный лист 1-А: маршрут не задан — показатели нулевые");
            return PassengerMetrics.empty();
        }
        return routeBased(route, capacity, days, from, to, money(kassa));
    }

    /**
     * Показатели по многодневному листу формы 3-С (ветка по {@code typeService}).
     *
     * @param typeService     значение колонки {@code type_service} (1/2/3)
     * @param route           маршрут (обязателен только для маршрутного такси)
     * @param brandCapacity   вместимость по марке (маршрутное такси)
     * @param vehicleCapacity вместимость самого ТС («свободное» такси)
     * @param days            рабочие дни листа
     * @param from            начало периода
     * @param to              конец периода
     * @param kassa           касса листа
     * @return показатели перевозки за период
     */
    public static PassengerMetrics forTaxi(Short typeService, RoutePassengerRef route,
                                           Integer brandCapacity, Integer vehicleCapacity,
                                           List<PassengerDay> days, LocalDate from, LocalDate to,
                                           BigDecimal kassa) {
        TaxiServiceType type = TaxiServiceType.byCode(typeService);
        if (type == null) {
            log.warn("Многодневный лист 3-С: тип обслуживания {} неизвестен — показатели нулевые", typeService);
            return PassengerMetrics.empty();
        }
        BigDecimal k = money(kassa);
        return switch (type) {
            case METER -> meterBased(vehicleCapacity, days, from, to, k);
            case HOURLY -> hourlyBased(days, from, to, k);
            case ROUTE -> {
                if (route == null) {
                    log.warn("Многодневный лист 3-С: маршрутное такси без маршрута — показатели нулевые");
                    yield PassengerMetrics.empty();
                }
                yield routeBased(route, brandCapacity, days, from, to, k);
            }
        };
    }

    // ------------------------------------------------------------ ветки расчёта

    private static PassengerMetrics routeBased(RoutePassengerRef route, Integer capacity,
                                               List<PassengerDay> days, LocalDate from, LocalDate to,
                                               BigDecimal kassa) {
        double routeDistance = (nz(route.distanceA()) + nz(route.distanceB())) / 2d;

        int workDays = 0;
        long laps = 0;
        double zeroRunTotal = 0d;
        int workMinutes = 0;

        for (PassengerDay day : safe(days)) {
            if (!inPeriod(day, from, to)) {
                continue;
            }
            workDays++;
            laps += day.laps() == null ? 0L : day.laps();
            zeroRunTotal += zeroRun(route, day.beginPathA()) + zeroRun(route, day.beginPathB());
            workMinutes += workMinutes(day);
        }

        double turnover = nz(capacity == null ? null : capacity.doubleValue())
                * nz(route.coeUseCapacity()) * routeDistance * laps;
        double passengerCount = CalcUtils.safeDivide(turnover, seatLength(route), 0d);
        double routeDistanceKm = routeDistance * laps;

        return new PassengerMetrics(workDays, laps,
                nz(route.plannedLap() == null ? null : route.plannedLap().doubleValue()) * workDays,
                turnover, passengerCount, routeDistanceKm, routeDistanceKm + zeroRunTotal,
                workMinutes, BigDecimal.ZERO, kassa, false);
    }

    private static PassengerMetrics meterBased(Integer vehicleCapacity, List<PassengerDay> days,
                                               LocalDate from, LocalDate to, BigDecimal kassa) {
        int workDays = 0;
        int workMinutes = 0;
        double totalDistance = 0d;
        for (PassengerDay day : safe(days)) {
            if (!inPeriod(day, from, to)) {
                continue;
            }
            workDays++;
            totalDistance += WaybillMath.distanceNonNegative(day.indicationCounterEntry(), day.indicationCounterExit());
            workMinutes += workMinutes(day);
        }
        double paidDistance = totalDistance * TAXI_PAID_RUN_SHARE;
        double passengerCount = CalcUtils.safeDivide(
                paidDistance * (nz(vehicleCapacity == null ? null : vehicleCapacity.doubleValue()) / 2d),
                TAXI_AVERAGE_TRIP_KM, 0d);
        double turnover = passengerCount * TAXI_AVERAGE_TRIP_KM;

        return new PassengerMetrics(workDays, 0L, 0d, turnover, passengerCount, paidDistance,
                totalDistance, workMinutes, BigDecimal.ZERO, kassa, true);
    }

    private static PassengerMetrics hourlyBased(List<PassengerDay> days, LocalDate from, LocalDate to,
                                                BigDecimal kassa) {
        int workDays = 0;
        int workMinutes = 0;
        double totalDistance = 0d;
        for (PassengerDay day : safe(days)) {
            if (!inPeriod(day, from, to)) {
                continue;
            }
            workDays++;
            workMinutes += workMinutes(day);
            if (day.indicationCounterEntry() != null && day.indicationCounterExit() != null) {
                totalDistance += WaybillMath.distanceNonNegative(
                        day.indicationCounterEntry(), day.indicationCounterExit());
            }
        }
        double passengerCount = CalcUtils.minutesToHours(workMinutes) * HOURLY_PASSENGERS_PER_HOUR;
        return new PassengerMetrics(workDays, 0L, 0d, passengerCount * TAXI_AVERAGE_TRIP_KM,
                passengerCount, 0d, totalDistance, workMinutes, BigDecimal.ZERO, kassa, true);
    }

    // ------------------------------------------------------------ вспомогательное

    private static boolean inPeriod(PassengerDay day, LocalDate from, LocalDate to) {
        LocalDate date = day.date();
        if (date == null) {
            log.warn("Рабочий день без даты пропущен при расчёте показателей (кругов: {})", day.laps());
            return false;
        }
        return (from == null || !date.isBefore(from)) && (to == null || !date.isAfter(to));
    }

    private static int workMinutes(PassengerDay day) {
        return day.workTimeInMinutes() != null
                ? day.workTimeInMinutes()
                : WaybillMath.workTimeMinutes(day.exitTime(), day.entryTime());
    }

    private static double seatLength(RoutePassengerRef route) {
        double value = nz(route.averageLengthPassSeat());
        return value == 0d ? 1d : value;
    }

    private static double zeroRun(RoutePassengerRef route, String selector) {
        if (selector == null) {
            return 0d;
        }
        return switch (selector.trim()) {
            case PATH_A -> nz(route.beginPathA());
            case PATH_B -> nz(route.beginPathB());
            default -> 0d;
        };
    }

    private static List<PassengerDay> safe(List<PassengerDay> days) {
        return days == null ? List.of() : days;
    }

    private static double nz(Double value) {
        return value == null ? 0d : value;
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
