package tj.mintrans.epd.waybill.calc.model;

import java.time.LocalDate;

/**
 * Запись справочника зимних коэффициентов ({@code fuel_winter_coef}).
 *
 * @param id         идентификатор записи
 * @param periodFrom начало зимнего периода (учитываются месяц и день)
 * @param periodTo   конец зимнего периода (учитываются месяц и день)
 * @param coef       коэффициент, % ({@code null} — не задан)
 */
public record WinterCoefRef(Long id, LocalDate periodFrom, LocalDate periodTo, Integer coef) {
}
