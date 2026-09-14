package tj.mintrans.epd.waybill.calc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tj.mintrans.epd.waybill.calc.model.DriverSalary;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalTime;

/**
 * Сводные величины путевого листа, не зависящие от справочников: пробег, рабочее время,
 * заработок водителя.
 *
 * <p>Перенос статических методов {@code tj.etrans.rohkhat.calc.WaybillCalculationService}
 * (§2.1 {@code BusBaseCalc.php}, §2.6 {@code BusCalc.php:342}). Оркестратор целиком
 * (метод {@code calculate(Waybill1ad, ...)}) портируется позже — он завязан на 6 форм ПЛ.</p>
 */
public final class WaybillMath {

    private static final Logger log = LoggerFactory.getLogger(WaybillMath.class);

    /** Делитель базы начисления заработка: {@code (earning / 4) * 3}. */
    private static final BigDecimal EARNING_DIVISOR = BigDecimal.valueOf(4);
    /** Множитель базы начисления заработка. */
    private static final BigDecimal EARNING_MULTIPLIER = BigDecimal.valueOf(3);
    /** Промежуточная точность деления выручки (до финального округления до 2 знаков). */
    private static final int INTERMEDIATE_SCALE = 10;

    /**
     * Предел правдоподобного пробега за один путевой лист, км.
     * Всё, что выше, — заглушки в унаследованных данных ({@code INT UNSIGNED} = 4 294 967 295).
     */
    private static final long MAX_REASONABLE_DISTANCE_KM = 1_000_000L;

    private WaybillMath() {
    }

    // -------------------------------------------------------------------- пробег

    /**
     * Пробег по показаниям одометра: {@code entry - exit}.
     * Пустые показания трактуются как 0; результат может быть отрицательным.
     * Недостоверные значения (заглушки {@code 4294967295}) обнуляются.
     *
     * @param indicationCounterEntry показание при возврате
     * @param indicationCounterExit  показание на выезде
     * @return разница показаний, км
     */
    public static int distance(Long indicationCounterEntry, Long indicationCounterExit) {
        long entry = indicationCounterEntry == null ? 0L : indicationCounterEntry;
        long exit = indicationCounterExit == null ? 0L : indicationCounterExit;
        long result = entry - exit;
        if (result > MAX_REASONABLE_DISTANCE_KM || result < -MAX_REASONABLE_DISTANCE_KM) {
            log.warn("Недостоверные показания одометра: выезд={}, возврат={} — пробег не учтён",
                    indicationCounterExit, indicationCounterEntry);
            return 0;
        }
        return (int) result;
    }

    /**
     * Пробег по показаниям одометра, не меньше нуля ({@code max(entry - exit, 0)}).
     *
     * @param indicationCounterEntry показание при возврате
     * @param indicationCounterExit  показание на выезде
     * @return разница показаний, км; 0 при отрицательной разнице
     */
    public static int distanceNonNegative(Long indicationCounterEntry, Long indicationCounterExit) {
        return Math.max(distance(indicationCounterEntry, indicationCounterExit), 0);
    }

    // ----------------------------------------------------------------- рабочее время

    /**
     * Рабочее время в минутах по времени выезда и возврата — модуль разницы
     * (как {@code Carbon::diffInMinutes}). Пустые значения → 0.
     *
     * @param exitTime  время выезда
     * @param entryTime время возврата
     * @return число минут (неотрицательное)
     */
    public static int workTimeMinutes(LocalTime exitTime, LocalTime entryTime) {
        if (exitTime == null || entryTime == null) {
            return 0;
        }
        return (int) Math.abs(Duration.between(exitTime, entryTime).toMinutes());
    }

    // ------------------------------------------------------------------ заработок

    /**
     * Заработок водителя.
     *
     * <pre>
     * salary = ((earning / 4) * 3) * percent_income + class_bonus
     * </pre>
     * <p>Порядок операций сохранён: сначала деление на 4, затем умножение на 3.
     * {@code percentIncome} — доля, а не проценты. Итог округляется до 2 знаков HALF_UP.</p>
     *
     * @param earning       выручка ({@code null} → 0)
     * @param percentIncome доля дохода компании ({@code null} → 0)
     * @param classBonus    надбавка за класс водителя ({@code null} → 0)
     * @return разложение заработка
     */
    public static DriverSalary driverSalary(BigDecimal earning, Double percentIncome, Number classBonus) {
        BigDecimal earningValue = earning == null ? BigDecimal.ZERO : earning;
        if (percentIncome == null) {
            log.warn("Заработок водителя: percent_income не заполнен, применён 0");
        }
        BigDecimal percent = percentIncome == null ? BigDecimal.ZERO : BigDecimal.valueOf(percentIncome);
        BigDecimal bonus = classBonus == null
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(classBonus.doubleValue());

        BigDecimal payableBase = earningValue
                .divide(EARNING_DIVISOR, INTERMEDIATE_SCALE, RoundingMode.HALF_UP)
                .multiply(EARNING_MULTIPLIER);

        BigDecimal salary = payableBase.multiply(percent).add(bonus)
                .setScale(CalcUtils.MONEY_SCALE, RoundingMode.HALF_UP);

        return new DriverSalary(
                CalcUtils.money(earningValue),
                payableBase.setScale(CalcUtils.MONEY_SCALE, RoundingMode.HALF_UP),
                percent,
                CalcUtils.money(bonus),
                salary);
    }

    /**
     * Надбавка за класс водителя из полей организации {@code companies.cat_1 / cat_2 / cat_3}.
     *
     * @param cat1   надбавка класса 1
     * @param cat2   надбавка класса 2
     * @param cat3   надбавка класса 3
     * @param degree класс/категория водителя (1, 2, 3)
     * @return надбавка, 0 при неизвестном классе или отсутствии значения
     */
    public static short classBonus(Short cat1, Short cat2, Short cat3, Integer degree) {
        if (degree == null) {
            return 0;
        }
        Short bonus = switch (degree) {
            case 1 -> cat1;
            case 2 -> cat2;
            case 3 -> cat3;
            default -> null;
        };
        return bonus == null ? 0 : bonus;
    }
}
