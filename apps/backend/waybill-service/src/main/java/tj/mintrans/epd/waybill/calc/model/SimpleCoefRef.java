package tj.mintrans.epd.waybill.calc.model;

/**
 * Запись справочника с единственным значением коэффициента —
 * {@code mountain_coef} либо {@code city_coef}.
 *
 * @param id   идентификатор записи справочника
 * @param coef значение коэффициента, % ({@code null} — не задано)
 */
public record SimpleCoefRef(Long id, Integer coef) {
}
