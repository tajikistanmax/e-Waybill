package tj.mintrans.epd.waybill.calc.report;

import java.time.LocalDate;
import java.util.List;

/**
 * Сводный региональный отчёт Минтранса (перенос {@code WaybillGeneralReport}, §6):
 * иерархия <b>Регион → Город → Предприятие</b> с показателями «план / факт» за
 * отчётный период текущего и прошлого года и суммированием вверх.
 *
 * <p>Разрез {@code transportation} — 12 показателей ({@link Indicators}).</p>
 */
public record RegionalReport(
        String billKind,      // PASSENGER | TAXI | CARGO
        String reportType,    // transportation
        LocalDate from,
        LocalDate to,
        int year,
        int prevYear,
        List<Region> regions,
        Indicators totals
) {

    public record Region(String title, Short regionId, List<City> cities, Indicators totals) {
    }

    public record City(String title, List<Company> companies, Indicators totals) {
    }

    public record Company(String title, String organizationRma, Indicators totals) {
    }

    /**
     * 12 показателей разреза {@code transportation}.
     *
     * @param planVolumeCur      total_1  план объёма, тыс. пасс., текущий год
     * @param factVolumeCur      total_2  факт объёма, текущий год
     * @param planVolumePrev     total_3  план объёма, прошлый год
     * @param factVolumePrev     total_4  факт объёма, прошлый год
     * @param volumeDoneCurPct   total_5  % = total_2·100/total_1
     * @param volumeDonePrevPct  total_6  % = total_4·100/total_3
     * @param planRotationCur    total_7  план оборота, млн пасс-км, текущий
     * @param factRotationCur    total_8  факт оборота, текущий
     * @param planRotationPrev   total_9  план оборота, прошлый
     * @param factRotationPrev   total_10 факт оборота, прошлый
     * @param rotationDoneCurPct total_11 % = total_8·100/total_7
     * @param rotationDonePrevPct total_12 % = total_10·100/total_9
     */
    public record Indicators(
            double planVolumeCur, double factVolumeCur, double planVolumePrev, double factVolumePrev,
            double volumeDoneCurPct, double volumeDonePrevPct,
            double planRotationCur, double factRotationCur, double planRotationPrev, double factRotationPrev,
            double rotationDoneCurPct, double rotationDonePrevPct
    ) {

        public static Indicators zero() {
            return new Indicators(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        /** Сложить план/факт-слагаемые (округляя каждое до 2 знаков, как в оригинале); % — пересчитать. */
        public Indicators plus(Indicators o) {
            double pvc = r2(planVolumeCur + o.planVolumeCur);
            double fvc = r2(factVolumeCur + o.factVolumeCur);
            double pvp = r2(planVolumePrev + o.planVolumePrev);
            double fvp = r2(factVolumePrev + o.factVolumePrev);
            double prc = r2(planRotationCur + o.planRotationCur);
            double frc = r2(factRotationCur + o.factRotationCur);
            double prp = r2(planRotationPrev + o.planRotationPrev);
            double frp = r2(factRotationPrev + o.factRotationPrev);
            return build(pvc, fvc, pvp, fvp, prc, frc, prp, frp);
        }

        public static Indicators build(double planVolumeCur, double factVolumeCur,
                                       double planVolumePrev, double factVolumePrev,
                                       double planRotationCur, double factRotationCur,
                                       double planRotationPrev, double factRotationPrev) {
            return new Indicators(
                    r2(planVolumeCur), r2(factVolumeCur), r2(planVolumePrev), r2(factVolumePrev),
                    pct(factVolumeCur, planVolumeCur), pct(factVolumePrev, planVolumePrev),
                    r2(planRotationCur), r2(factRotationCur), r2(planRotationPrev), r2(factRotationPrev),
                    pct(factRotationCur, planRotationCur), pct(factRotationPrev, planRotationPrev));
        }

        private static double pct(double fact, double plan) {
            return plan > 0 ? r2(fact * 100.0 / plan) : 0d;
        }

        private static double r2(double v) {
            return Math.round(v * 100.0) / 100.0;
        }
    }
}
