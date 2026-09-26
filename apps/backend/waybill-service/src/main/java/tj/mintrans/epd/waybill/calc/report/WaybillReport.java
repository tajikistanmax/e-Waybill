package tj.mintrans.epd.waybill.calc.report;

import java.time.LocalDate;
import java.util.List;

/**
 * Готовый отчёт: строки по значениям группировки + итоговая строка.
 *
 * @param type            тип разреза
 * @param typeLabel       подпись типа
 * @param from            начало периода
 * @param to              конец периода
 * @param organizationRma организация (или {@code null} — по всем, для платформенных ролей)
 * @param rows            строки отчёта
 * @param totals          строка «ИТОГО»
 * @param subtotals       промежуточные итоги (legacy: по видам маршрутов у «Хатсайр», по депо у троллейбусов;
 *                        сверка 25.09, D13); пусто — нет
 */
public record WaybillReport(
        ReportType type,
        String typeLabel,
        LocalDate from,
        LocalDate to,
        String organizationRma,
        List<ReportRow> rows,
        ReportRow totals,
        List<ReportRow> subtotals
) {
    public WaybillReport(ReportType type, String typeLabel, LocalDate from, LocalDate to, String organizationRma,
                         List<ReportRow> rows, ReportRow totals) {
        this(type, typeLabel, from, to, organizationRma, rows, totals, List.of());
    }
}
