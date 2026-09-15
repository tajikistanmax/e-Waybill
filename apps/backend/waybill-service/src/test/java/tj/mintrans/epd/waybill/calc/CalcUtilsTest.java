package tj.mintrans.epd.waybill.calc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Тесты утилит расчётного ядра ({@code helpers.php}, §1.1 спецификации «Роҳхат»).
 * Портированы из rohkhat-v2 ({@code CalcUtilsTest}).
 */
class CalcUtilsTest {

    @Test
    @DisplayName("timeToMinutes: часы*60 + минуты, секунды отбрасываются")
    void timeToMinutesDropsSeconds() {
        assertThat(CalcUtils.timeToMinutes(LocalTime.of(7, 30, 45))).isEqualTo(450);
        assertThat(CalcUtils.timeToMinutes(LocalTime.of(0, 0))).isZero();
    }

    @Test
    @DisplayName("timeToMinutes: null даёт 0, а не NPE")
    void timeToMinutesNull() {
        assertThat(CalcUtils.timeToMinutes(null)).isZero();
    }

    @Test
    @DisplayName("timeToHours: round(h + m/60, 2)")
    void timeToHours() {
        assertThat(CalcUtils.timeToHours(LocalTime.of(7, 30))).isEqualTo(7.5);
        assertThat(CalcUtils.timeToHours(LocalTime.of(7, 20))).isEqualTo(7.33);
        assertThat(CalcUtils.timeToHours(null)).isZero();
    }

    @Test
    @DisplayName("minutesToHours: округление до 2 знаков")
    void minutesToHours() {
        assertThat(CalcUtils.minutesToHours(480)).isEqualTo(8.0);
        assertThat(CalcUtils.minutesToHours(440)).isEqualTo(7.33);
        assertThat(CalcUtils.minutesToHours(0)).isZero();
    }

    @Test
    @DisplayName("round: HALF_UP как в PHP round()")
    void roundHalfUp() {
        assertThat(CalcUtils.round(2.345, 2)).isEqualTo(2.35);
        assertThat(CalcUtils.round(-2.345, 2)).isEqualTo(-2.35);
        assertThat(CalcUtils.round(15.599999999999977, 4)).isEqualTo(15.6);
    }

    @Test
    @DisplayName("round: NaN/Infinity не ломают расчёт")
    void roundNotFinite() {
        assertThat(CalcUtils.round(Double.NaN, 2)).isZero();
        assertThat(CalcUtils.round(Double.POSITIVE_INFINITY, 2)).isZero();
    }

    @Test
    @DisplayName("safeDivide: деление на ноль даёт значение по умолчанию")
    void safeDivideByZero() {
        assertThat(CalcUtils.safeDivide(10, 0, 1)).isEqualTo(1);
        assertThat(CalcUtils.safeDivide(10, 4, 1)).isEqualTo(2.5);
        assertThat(CalcUtils.safeDivide(10, Double.NaN, 7)).isEqualTo(7);
    }

    @Test
    @DisplayName("money: округление до 2 знаков HALF_UP")
    void money() {
        assertThat(CalcUtils.money(new BigDecimal("10.005"))).isEqualByComparingTo("10.01");
        assertThat(CalcUtils.money((BigDecimal) null)).isEqualByComparingTo("0.00");
        assertThat(CalcUtils.money(12.345d)).isEqualByComparingTo("12.35");
        assertThat(CalcUtils.money((Double) null)).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("nz: null трактуется как 0")
    void nz() {
        assertThat(CalcUtils.nz(null)).isZero();
        assertThat(CalcUtils.nz(5.5d)).isEqualTo(5.5);
    }

    @Test
    @DisplayName("parseLegacyNumber: запятая как десятичный разделитель")
    void parseLegacyNumber() {
        assertThat(CalcUtils.parseLegacyNumber("31,5")).isCloseTo(31.5, within(1e-9));
        assertThat(CalcUtils.parseLegacyNumber("31.5")).isCloseTo(31.5, within(1e-9));
        assertThat(CalcUtils.parseLegacyNumber(" 7 ")).isCloseTo(7d, within(1e-9));
    }

    @Test
    @DisplayName("parseLegacyNumber: null/мусор дают 0, а не исключение")
    void parseLegacyNumberInvalid() {
        assertThat(CalcUtils.parseLegacyNumber(null)).isZero();
        assertThat(CalcUtils.parseLegacyNumber("")).isZero();
        assertThat(CalcUtils.parseLegacyNumber("не число")).isZero();
    }
}
