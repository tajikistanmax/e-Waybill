package tj.mintrans.epd.waybill.calc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tj.mintrans.epd.waybill.calc.model.TariffAmount;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Расчёт стоимости по тарифам маршрута ({@code tariffs.price_per_1_mkm},
 * {@code tariffs.price_one_time}) и стоимости услуг ({@code brands.cost_services}).
 *
 * <p>Перенос {@code tj.etrans.rohkhat.calc.TariffService} без части, зависящей от
 * {@code TariffRepository} — поиск тарифа станет тонким адаптером в фазе «Справочники».
 * Здесь только денежные формулы: scale = 2, {@link RoundingMode#HALF_UP}.</p>
 *
 * <p><b>РАСХОЖДЕНИЕ С ОРИГИНАЛОМ:</b> в исходной системе таблица {@code tariffs} использовалась
 * только для CRUD; расчётной формулы по ней не было. Принята естественная трактовка колонок.</p>
 */
public final class TariffMath {

    private static final Logger log = LoggerFactory.getLogger(TariffMath.class);

    /** Число знаков после запятой для денежных величин. */
    public static final int MONEY_SCALE = CalcUtils.MONEY_SCALE;

    private TariffMath() {
    }

    /**
     * Стоимость пробега: {@code price_per_1_mkm * км}.
     *
     * @param pricePer1Mkm цена за 1 машино-км ({@code null} → 0)
     * @param distanceKm   пробег, км
     * @return сумма, scale = 2
     */
    public static BigDecimal distanceCost(Double pricePer1Mkm, double distanceKm) {
        if (pricePer1Mkm == null) {
            log.warn("Стоимость пробега: price_per_1_mkm не заполнен, применён 0");
            return zero();
        }
        if (distanceKm <= 0d) {
            return zero();
        }
        return BigDecimal.valueOf(pricePer1Mkm)
                .multiply(BigDecimal.valueOf(distanceKm))
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Стоимость поездок: {@code price_one_time * число поездок}.
     *
     * @param priceOneTime цена за одну поездку ({@code null} → 0)
     * @param trips        число поездок (отрицательное → 0)
     * @return сумма, scale = 2
     */
    public static BigDecimal tripCost(Double priceOneTime, int trips) {
        if (priceOneTime == null) {
            log.warn("Стоимость поездок: price_one_time не заполнен, применён 0");
            return zero();
        }
        if (trips <= 0) {
            return zero();
        }
        return BigDecimal.valueOf(priceOneTime)
                .multiply(BigDecimal.valueOf(trips))
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Стоимость услуг марки ТС: {@code cost_services * количество}.
     *
     * @param costServices стоимость одной услуги ({@code null} → 0)
     * @param quantity     количество услуг
     * @return сумма, scale = 2
     */
    public static BigDecimal serviceCost(Double costServices, int quantity) {
        if (costServices == null || quantity <= 0) {
            return zero();
        }
        return BigDecimal.valueOf(costServices)
                .multiply(BigDecimal.valueOf(quantity))
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Сводный расчёт по составляющим.
     *
     * @param pricePer1Mkm  цена за 1 машино-км ({@code null} → 0)
     * @param priceOneTime  цена за одну поездку ({@code null} → 0)
     * @param costServices  стоимость одной услуги ({@code null} → 0)
     * @param distanceKm    пробег, км
     * @param trips         число поездок
     * @param serviceCount  количество услуг
     * @return разложение и итог
     */
    public static TariffAmount calculate(Double pricePer1Mkm, Double priceOneTime, Double costServices,
                                         double distanceKm, int trips, int serviceCount) {
        BigDecimal distance = distanceCost(pricePer1Mkm, distanceKm);
        BigDecimal trip = tripCost(priceOneTime, trips);
        BigDecimal services = serviceCost(costServices, serviceCount);
        BigDecimal total = distance.add(trip).add(services).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        return new TariffAmount(distance, trip, services, total);
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
