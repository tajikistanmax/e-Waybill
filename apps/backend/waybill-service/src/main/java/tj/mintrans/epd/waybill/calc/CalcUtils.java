package tj.mintrans.epd.waybill.calc;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalTime;

/**
 * Низкоуровневые утилиты расчётного ядра — перенос глобальных хелперов системы «Роҳхат»
 * (эталон: {@code app/helpers.php}, docs/spec/07-calculations.md §1.1).
 *
 * <p>Портировано дословно из rohkhat-v2 ({@code tj.etrans.rohkhat.calc.CalcUtils}).</p>
 */
public final class CalcUtils {

    /** Округление литров/километров (остаток топлива округляется до 4 знаков). */
    public static final int FUEL_SCALE = 4;

    /** Округление денежных величин. */
    public static final int MONEY_SCALE = 2;

    private CalcUtils() {
    }

    /**
     * Время в минуты. Оригинал: {@code helpers.php:12, time_to_minutes()} —
     * {@code hms[0]*60 + hms[1]}; секунды ОТБРАСЫВАЮТСЯ, пустое значение даёт 0.
     *
     * @param time время (может быть {@code null})
     * @return число минут, 0 при {@code null}
     */
    public static int timeToMinutes(LocalTime time) {
        if (time == null) {
            return 0;
        }
        return time.getHour() * 60 + time.getMinute();
    }

    /**
     * Время в часы с округлением до 2 знаков. Оригинал: {@code helpers.php:23, time_to_hours()}.
     *
     * @param time время (может быть {@code null})
     * @return число часов, 0 при {@code null}
     */
    public static double timeToHours(LocalTime time) {
        if (time == null) {
            return 0d;
        }
        return round(time.getHour() + time.getMinute() / 60d, 2);
    }

    /**
     * Минуты в часы с округлением до 2 знаков.
     *
     * @param minutes число минут
     * @return число часов
     */
    public static double minutesToHours(int minutes) {
        return round(minutes / 60d, 2);
    }

    /**
     * Округление {@code double} по правилу HALF_UP (PHP {@code round()} использует ту же стратегию).
     *
     * @param value значение
     * @param scale число знаков после запятой
     * @return округлённое значение
     */
    public static double round(double value, int scale) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0d;
        }
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP).doubleValue();
    }

    /**
     * Округление денежной величины: scale = 2, {@link RoundingMode#HALF_UP}.
     *
     * @param value значение ({@code null} трактуется как 0)
     * @return округлённая сумма, никогда не {@code null}
     */
    public static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Округление денежной величины из {@code double}.
     *
     * @param value значение ({@code null} трактуется как 0)
     * @return округлённая сумма, никогда не {@code null}
     */
    public static BigDecimal money(Double value) {
        return money(value == null ? BigDecimal.ZERO : BigDecimal.valueOf(value));
    }

    /**
     * Безопасное деление: при нулевом (или NaN) делителе возвращает значение по умолчанию.
     *
     * @param dividend делимое
     * @param divisor  делитель
     * @param fallback значение при нулевом делителе
     * @return частное либо {@code fallback}
     */
    public static double safeDivide(double dividend, double divisor, double fallback) {
        if (divisor == 0d || Double.isNaN(divisor)) {
            return fallback;
        }
        double result = dividend / divisor;
        return Double.isFinite(result) ? result : fallback;
    }

    /**
     * {@code null} → 0.
     *
     * @param value значение
     * @return {@code double}-значение либо 0
     */
    public static double nz(Number value) {
        return value == null ? 0d : value.doubleValue();
    }

    /**
     * Разбор числа из унаследованного JSON: значение может быть строкой с запятой
     * в роли десятичного разделителя ({@code "12,5"}).
     * Оригинал: {@code (float) str_replace(',', '.', $fuel)}.
     *
     * @param raw исходная строка
     * @return число, 0 если разобрать не удалось
     */
    public static double parseLegacyNumber(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0d;
        }
        try {
            return Double.parseDouble(raw.trim().replace(',', '.'));
        } catch (NumberFormatException ex) {
            return 0d;
        }
    }
}
