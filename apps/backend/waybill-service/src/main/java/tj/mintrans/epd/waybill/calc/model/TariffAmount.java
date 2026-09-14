package tj.mintrans.epd.waybill.calc.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Разложение стоимости по тарифу маршрута.
 *
 * <p>Все величины — {@link BigDecimal} со scale = 2 и {@link RoundingMode#HALF_UP}.</p>
 *
 * @param distanceAmount стоимость пробега ({@code price_per_1_mkm * км})
 * @param tripAmount     стоимость поездок ({@code price_one_time * число поездок})
 * @param serviceAmount  стоимость услуг марки ({@code brands.cost_services * количество})
 * @param total          итоговая сумма
 */
public record TariffAmount(
        BigDecimal distanceAmount,
        BigDecimal tripAmount,
        BigDecimal serviceAmount,
        BigDecimal total
) {

    /** Нулевой результат со scale = 2. */
    public static TariffAmount zero() {
        BigDecimal z = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        return new TariffAmount(z, z, z, z);
    }
}
