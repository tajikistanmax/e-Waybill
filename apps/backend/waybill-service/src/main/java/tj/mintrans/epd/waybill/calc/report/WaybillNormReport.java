package tj.mintrans.epd.waybill.calc.report;

import java.time.LocalDate;
import java.util.List;

/**
 * Отчёт «Норматив выдачи путевых листов» (перенос {@code Type2\WaybillType2CountReport}, §6.4).
 *
 * <p>По каждому предприятию: сколько ПЛ выбранного вида выдано за период против норматива
 * (число стоянок/ТС соответствующего вида × норма листов на стоянку).</p>
 */
public record WaybillNormReport(
        String waybillType,
        String waybillTypeLabel,
        int mustGivePerParking,
        LocalDate from,
        LocalDate to,
        List<Row> rows,
        Row totals
) {

    /**
     * @param issued        total_1 — выдано ПЛ за период
     * @param parkings      total_2 — стоянок (ТС) соответствующего вида
     * @param perParking    total_3 — выдано на стоянку = issued / parkings (округл. 3)
     * @param mustGive      total_4 — норматив = parkings × mustGivePerParking
     * @param deviation     total_5 — отклонение = issued − mustGive
     */
    public record Row(String organizationRma, String organizationName, String regionTitle,
                      long issued, long parkings, double perParking, long mustGive, long deviation) {

        public static Row zero(String rma, String name, String region) {
            return new Row(rma, name, region, 0, 0, 0, 0, 0);
        }
    }
}
