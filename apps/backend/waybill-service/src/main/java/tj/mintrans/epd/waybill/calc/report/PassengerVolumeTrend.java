package tj.mintrans.epd.waybill.calc.report;

import java.util.List;

/**
 * Тренд пассажирооборота (млн пасс-км) по месяцам для автобусного/троллейбусного парка —
 * перенос единственного содержательного графика легаси-панели администратора
 * ({@code Admin\Charts\Ebus\PassengerVolumeController}: вместимость × коэффициент
 * использования × расстояние маршрута × число рейсов, накопленное по завершённым ПЛ).
 *
 * <p>Мультиарендность: как остальные {@code /api/v1/reports/*} — тенант видит только
 * свою область, платформенные роли — все организации.</p>
 */
public record PassengerVolumeTrend(int months, List<Point> points) {

    /** Один месяц тренда: ключ {@code yyyy-MM} и суммарный оборот, млн пасс-км. */
    public record Point(String month, double turnoverMillion) {
    }
}
