package tj.mintrans.epd.waybill.calc.model;

/**
 * Запись справочника коэффициентов износа ({@code used_coef}).
 *
 * <p>Порог: если возраст ТС строго больше {@code year} И пробег строго больше {@code km},
 * применяется {@code coef}. Выбирается максимальный подходящий.</p>
 *
 * @param year минимальный возраст ТС (лет), не включительно
 * @param km   минимальный пробег (км), не включительно
 * @param coef надбавка износа, %
 */
public record UsedCoefRef(Integer year, Long km, Integer coef) {
}
