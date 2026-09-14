package tj.mintrans.epd.waybill.calc.report;

/** Измерение группировки строк отчёта. */
public enum ReportGrouping {
    /** По транспортному средству (госномер). */
    VEHICLE,
    /** По маршруту. */
    ROUTE,
    /** По марке ТС. */
    BRAND,
    /** По водителю. */
    DRIVER,
    /** Без группировки — одна итоговая строка. */
    NONE,
    /** Строка на каждый путевой лист (реестр/журнал). */
    PER_WAYBILL
}
