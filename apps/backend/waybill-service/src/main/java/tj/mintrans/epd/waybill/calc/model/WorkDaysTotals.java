package tj.mintrans.epd.waybill.calc.model;

/**
 * Агрегаты по рабочим дням многодневного путевого листа за период.
 *
 * @param workDays        число дней в периоде
 * @param distanceKm      суммарный пробег, км
 * @param workTimeMinutes суммарное рабочее время, мин
 */
public record WorkDaysTotals(int workDays, int distanceKm, int workTimeMinutes) {

    /** Пустой результат (нет дней в периоде). */
    public static WorkDaysTotals empty() {
        return new WorkDaysTotals(0, 0, 0);
    }
}
