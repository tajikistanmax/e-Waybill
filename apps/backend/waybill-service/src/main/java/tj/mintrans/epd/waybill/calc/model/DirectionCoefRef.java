package tj.mintrans.epd.waybill.calc.model;

/**
 * Коэффициентные поля направления ({@code directions}) — грузовая ветка (форма 2-Б).
 *
 * <p>В отличие от {@link RouteCoefRef}, здесь {@code mountainCoefId} и {@code inCityCoefId} —
 * НАСТОЯЩИЕ КЛЮЧИ справочников {@code mountain_coef} / {@code city_coef} (в данных 1, 2, 3),
 * и значение коэффициента ищется по справочнику (эталон {@code CargoFuelBase.php:163}).</p>
 *
 * @param winterCoefId  ключ записи {@code fuel_winter_coef}
 * @param mountainCoefId ключ записи {@code mountain_coef}
 * @param inCityCoefId  ключ записи {@code city_coef}
 */
public record DirectionCoefRef(Long winterCoefId, Long mountainCoefId, Long inCityCoefId) {
}
