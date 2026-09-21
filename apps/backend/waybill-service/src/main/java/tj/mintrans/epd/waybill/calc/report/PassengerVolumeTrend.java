package tj.mintrans.epd.waybill.calc.report;

import tj.mintrans.epd.waybill.domain.WaybillType;

import java.util.List;

/**
 * Тренд панели: пассажирооборот и число выписанных ПЛ по месяцам — перенос легаси-графиков
 * {@code Admin\Charts\Ebus\PassengerVolumeController} (объём) и {@code Ebus\BillCountsController}
 * (COUNT ПЛ по MONTH(created_at)) панели {@code dashboard/ebus} (MIGRATION.md 6.8).
 *
 * <p>Мультиарендность: как остальные {@code /api/v1/reports/*} — тенант видит только
 * свою область, платформенные роли — все организации.</p>
 *
 * @param months число месяцев (включая текущий)
 * @param types  виды ПЛ, по которым построен тренд (автобус и/или троллейбус)
 * @param points точки по месяцам, от старого к новому
 */
public record PassengerVolumeTrend(int months, List<WaybillType> types, List<Point> points) {

    /**
     * Один месяц тренда.
     *
     * @param month           ключ {@code yyyy-MM}
     * @param turnoverMillion суммарный пассажирооборот завершённых ПЛ, млн пасс-км
     * @param waybills        число ПЛ, выписанных в месяце (все статусы — как legacy COUNT по created_at)
     */
    public record Point(String month, double turnoverMillion, long waybills) {
    }
}
