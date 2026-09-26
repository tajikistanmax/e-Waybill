package tj.mintrans.epd.waybill.calc.report;

import java.time.LocalDate;
import java.util.List;

/**
 * Сводный отчёт «Количество путевых листов» (перенос {@code AbstractCalcCountTotal}, §6.3):
 * 19 счётчиков в иерархии Регион → Город → Предприятие с суммированием вверх.
 *
 * <p>Адаптация к модели e-Waybill: «выдан» = присвоен номер (статус READY и далее);
 * «обработан» = зафиксирован одометр возврата (Т5 и далее). Период — по {@code createdAt}.</p>
 */
public record RegionalCountReport(
        String billKind,   // PASSENGER | CARGO | ALL
        LocalDate from,
        LocalDate to,
        int year,
        int prevYear,
        List<Region> regions,
        Counts totals
) {

    public record Region(String title, Short regionId, List<City> cities, Counts totals) {
    }

    public record City(String title, List<Company> companies, Counts totals) {
    }

    public record Company(String title, String organizationRma, Counts totals) {
    }

    /**
     * 19 показателей.
     *
     * @param issuedMonth      total_1  выдано за отчётный период (месяц)
     * @param issuedPrevMonth  total_2  выдано за предыдущий месяц
     * @param issuedMonthDelta total_3  = total_1 − total_2
     * @param issuedYtd        total_4  выдано с начала года
     * @param issuedYtdPrev    total_5  выдано с начала прошлого года (тот же отрезок)
     * @param issuedYtdDelta   total_6  = total_4 − total_5
     * @param processedMonth       total_7  обработано за месяц
     * @param processedPrevMonth   total_8  обработано за предыдущий месяц
     * @param processedMonthDelta  total_9  = total_7 − total_8
     * @param processedYtd         total_10 обработано с начала года
     * @param processedYtdPrev     total_11 обработано с начала прошлого года
     * @param processedYtdDelta    total_12 = total_10 − total_11
     * @param unprocessedYtd       total_13 = total_4 − total_10 (необработанные с начала года)
     * @param vehiclesYtd     total_14 уникальных ТС с начала года
     * @param vehiclesMonth   total_15 уникальных ТС за месяц
     * @param vehiclesPrevMonth total_16 уникальных ТС за предыдущий месяц
     * @param vehiclesMonthPrevYear total_17 уникальных ТС за тот же месяц прошлого года
     * @param vehiclesMonthDelta    total_18 = total_15 − total_16
     * @param vehiclesYoYDelta      total_19 = total_15 − total_17
     * @param cargoWaybillsTotal          total_20 грузовых ПЛ (2-Б/5Б-БМ/спецтехника/опасные грузы), выдано за период
     * @param cargoWaybillsWithConsignment total_21 число борхатов периода (legacy CargoAttachWaybillCountReport);
     *                                     у листа без борхатов с накладной в данных листа (СМР 5Б-БМ) — 1
     *        (присутствие определяется по непустому {@code typeData.senderName})
     */
    public record Counts(
            long issuedMonth, long issuedPrevMonth, long issuedMonthDelta,
            long issuedYtd, long issuedYtdPrev, long issuedYtdDelta,
            long processedMonth, long processedPrevMonth, long processedMonthDelta,
            long processedYtd, long processedYtdPrev, long processedYtdDelta,
            long unprocessedYtd,
            long vehiclesYtd, long vehiclesMonth, long vehiclesPrevMonth, long vehiclesMonthPrevYear,
            long vehiclesMonthDelta, long vehiclesYoYDelta,
            long cargoWaybillsTotal, long cargoWaybillsWithConsignment
    ) {

        public static Counts zero() {
            return new Counts(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        public static Counts of(long issuedMonth, long issuedPrevMonth, long issuedYtd, long issuedYtdPrev,
                                long processedMonth, long processedPrevMonth, long processedYtd, long processedYtdPrev,
                                long vehiclesYtd, long vehiclesMonth, long vehiclesPrevMonth, long vehiclesMonthPrevYear,
                                long cargoWaybillsTotal, long cargoWaybillsWithConsignment) {
            return new Counts(
                    issuedMonth, issuedPrevMonth, issuedMonth - issuedPrevMonth,
                    issuedYtd, issuedYtdPrev, issuedYtd - issuedYtdPrev,
                    processedMonth, processedPrevMonth, processedMonth - processedPrevMonth,
                    processedYtd, processedYtdPrev, processedYtd - processedYtdPrev,
                    issuedYtd - processedYtd,
                    vehiclesYtd, vehiclesMonth, vehiclesPrevMonth, vehiclesMonthPrevYear,
                    vehiclesMonth - vehiclesPrevMonth, vehiclesMonth - vehiclesMonthPrevYear,
                    cargoWaybillsTotal, cargoWaybillsWithConsignment);
        }

        /** Сложить базовые счётчики двух узлов; разницы пересчитать. */
        public Counts plus(Counts o) {
            return of(
                    issuedMonth + o.issuedMonth, issuedPrevMonth + o.issuedPrevMonth,
                    issuedYtd + o.issuedYtd, issuedYtdPrev + o.issuedYtdPrev,
                    processedMonth + o.processedMonth, processedPrevMonth + o.processedPrevMonth,
                    processedYtd + o.processedYtd, processedYtdPrev + o.processedYtdPrev,
                    vehiclesYtd + o.vehiclesYtd, vehiclesMonth + o.vehiclesMonth,
                    vehiclesPrevMonth + o.vehiclesPrevMonth, vehiclesMonthPrevYear + o.vehiclesMonthPrevYear,
                    cargoWaybillsTotal + o.cargoWaybillsTotal, cargoWaybillsWithConsignment + o.cargoWaybillsWithConsignment);
        }
    }
}
