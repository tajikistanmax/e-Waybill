package tj.mintrans.epd.waybill.calc.model;

import java.math.BigDecimal;

/**
 * Заработок водителя по путевому листу.
 *
 * <p>Формула (эталон docs/spec/07-calculations.md §2.6, {@code BusCalc.php:342, type5()}):</p>
 * <pre>
 * salary = ((earning / 4) * 3) * company.percent_income + company.cat_{degree}
 * </pre>
 *
 * @param earning       выручка по листу
 * @param payableBase   база начисления = {@code (earning / 4) * 3} — 75 % выручки
 * @param percentIncome доля компании ({@code companies.percent_income}; доля, не проценты)
 * @param classBonus    надбавка за класс водителя ({@code companies.cat_1..cat_3})
 * @param salary        итог, округлён до 2 знаков (HALF_UP)
 */
public record DriverSalary(
        BigDecimal earning,
        BigDecimal payableBase,
        BigDecimal percentIncome,
        BigDecimal classBonus,
        BigDecimal salary
) {
}
