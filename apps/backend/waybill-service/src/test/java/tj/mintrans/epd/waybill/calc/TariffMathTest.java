package tj.mintrans.epd.waybill.calc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tj.mintrans.epd.waybill.calc.model.TariffAmount;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тесты денежных формул по тарифам ({@code tariffs.price_per_1_mkm}, {@code tariffs.price_one_time})
 * и стоимости услуг ({@code brands.cost_services}). Портированы из rohkhat-v2 ({@code TariffServiceTest}).
 */
class TariffMathTest {

    @Test
    @DisplayName("стоимость пробега: price_per_1_mkm * км, округление до 2 знаков HALF_UP")
    void distanceCost() {
        assertThat(TariffMath.distanceCost(2.5d, 100d)).isEqualByComparingTo("250.00");
        assertThat(TariffMath.distanceCost(0.335d, 1d)).isEqualByComparingTo("0.34");
    }

    @Test
    @DisplayName("стоимость пробега: граничные случаи — нулевой пробег и пустая цена")
    void distanceCostEdgeCases() {
        assertThat(TariffMath.distanceCost(2.5d, 0d)).isEqualByComparingTo("0.00");
        assertThat(TariffMath.distanceCost(2.5d, -10d)).isEqualByComparingTo("0.00");
        assertThat(TariffMath.distanceCost(null, 100d)).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("стоимость поездок: price_one_time * число поездок")
    void tripCost() {
        assertThat(TariffMath.tripCost(1.5d, 3)).isEqualByComparingTo("4.50");
        assertThat(TariffMath.tripCost(1.5d, 0)).isEqualByComparingTo("0.00");
        assertThat(TariffMath.tripCost(null, 3)).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("стоимость услуг: cost_services * количество")
    void serviceCost() {
        assertThat(TariffMath.serviceCost(10d, 2)).isEqualByComparingTo("20.00");
        assertThat(TariffMath.serviceCost(null, 2)).isEqualByComparingTo("0.00");
        assertThat(TariffMath.serviceCost(10d, 0)).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("сводный расчёт: 250.00 + 4.50 + 20.00 = 274.50, scale = 2")
    void calculateTotal() {
        TariffAmount amount = TariffMath.calculate(2.5d, 1.5d, 10d, 100d, 3, 2);

        assertThat(amount.distanceAmount()).isEqualByComparingTo("250.00");
        assertThat(amount.tripAmount()).isEqualByComparingTo("4.50");
        assertThat(amount.serviceAmount()).isEqualByComparingTo("20.00");
        assertThat(amount.total()).isEqualByComparingTo("274.50");
        assertThat(amount.total().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("сводный расчёт без услуг")
    void calculateWithoutServices() {
        TariffAmount amount = TariffMath.calculate(2.5d, 1.5d, null, 100d, 3, 0);

        assertThat(amount.serviceAmount()).isEqualByComparingTo("0.00");
        assertThat(amount.total()).isEqualByComparingTo("254.50");
    }

    @Test
    @DisplayName("отсутствие тарифа: суммы пробега и поездок нулевые, услуги учитываются")
    void calculateWithoutTariff() {
        TariffAmount amount = TariffMath.calculate(null, null, 10d, 100d, 3, 2);

        assertThat(amount.distanceAmount()).isEqualByComparingTo("0.00");
        assertThat(amount.tripAmount()).isEqualByComparingTo("0.00");
        assertThat(amount.serviceAmount()).isEqualByComparingTo("20.00");
        assertThat(amount.total()).isEqualByComparingTo("20.00");
    }

    @Test
    @DisplayName("нулевой результат TariffAmount.zero() имеет scale = 2")
    void zeroAmount() {
        TariffAmount zero = TariffAmount.zero();

        assertThat(zero.total()).isEqualByComparingTo("0.00");
        assertThat(zero.total().scale()).isEqualTo(2);
    }
}
