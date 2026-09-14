package tj.mintrans.epd.waybill.calc.report;

/**
 * Типы разрезов пассажирского/грузового отчёта — перенос
 * {@code config('trans.report.report_type')} из ИС «Роҳхат»
 * (docs/spec/06-admin-reports-charts.md §1).
 *
 * <p>Вместо динамической диспетчеризации {@code $calc->{'type'.$N}(...)} оригинала —
 * перечисление со стратегией группировки, чтобы отсутствующие комбинации ловились
 * компилятором (рекомендация §9 спецификации).</p>
 */
public enum ReportType {

    /** Автомобил — по транспортному средству. */
    BY_VEHICLE("По транспортным средствам", ReportGrouping.VEHICLE),
    /** Хатсайр — по маршруту. */
    BY_ROUTE("По маршрутам", ReportGrouping.ROUTE),
    /** Тамға — по марке ТС. */
    BY_BRAND("По маркам ТС", ReportGrouping.BRAND),
    /** Табел — по водителю. */
    BY_DRIVER("По водителям", ReportGrouping.DRIVER),
    /** Авто — сводка по предприятию. */
    COMPANY_SUMMARY("Сводка по предприятию", ReportGrouping.NONE),
    /** Музди меҳнати ронандагон — заработок водителей. */
    DRIVER_SALARY("Заработок водителей", ReportGrouping.DRIVER),
    /** Малумот оиди гашт — сведения о рейсах (счётчики). */
    TRIP_INFO("Сведения о рейсах", ReportGrouping.NONE),
    /** Дафтари қайди в/н — реестр (журнал) путевых листов. */
    REGISTRY_JOURNAL("Реестр путевых листов", ReportGrouping.PER_WAYBILL),
    /** Сузишвори (тип 9) — общий (сводный) топливный свод. */
    FUEL_GENERAL("Топливо — сводно", ReportGrouping.NONE),
    /**
     * Хисоботи сузишвори (тип 10) — детальный топливный отчёт по каждому путевому листу.
     * Отдельный от {@link #FUEL_GENERAL} разрез боевого отчёта: там сводный итог (свод),
     * здесь — построчный отчёт по документам (пробег + топливо по каждому ПЛ, с итогом).
     */
    FUEL_BY_WAYBILL("Топливо — отчёт по путевым листам", ReportGrouping.PER_WAYBILL),
    /** Хисоботи сузишвории автомобил (тип 12) — топливо по ТС. */
    FUEL_BY_VEHICLE("Топливо по ТС", ReportGrouping.VEHICLE),
    /** Хисоботи сузишвории ронанда (тип 11) — топливо по водителю. */
    FUEL_BY_DRIVER("Топливо по водителям", ReportGrouping.DRIVER);

    private final String label;
    private final ReportGrouping grouping;

    ReportType(String label, ReportGrouping grouping) {
        this.label = label;
        this.grouping = grouping;
    }

    public String label() {
        return label;
    }

    public ReportGrouping grouping() {
        return grouping;
    }
}
