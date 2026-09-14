package tj.mintrans.epd.waybill.calc.model;

/**
 * Коэффициентные поля маршрута ({@code routes}) — пассажирская ветка.
 *
 * <p>ВНИМАНИЕ: {@code mountainCoefValue} и {@code inCityCoefValue} — это САМИ ЗНАЧЕНИЯ
 * коэффициентов (в данных 5, 10, 15, 20), а НЕ ключи справочника. Так их хранит
 * унаследованная схема, и порт складывает их напрямую (эталон {@code helpers.php:137}).</p>
 *
 * @param winterCoefId     ключ записи {@code fuel_winter_coef} ({@code null} — не задан)
 * @param mountainCoefValue значение горного коэффициента, % ({@code null} — 0)
 * @param inCityCoefValue  значение городского коэффициента, % ({@code null} — 0)
 * @param stationCoef      остановочный коэффициент {@code routes.station_coef}, % ({@code null} — 0)
 * @param roadQuality      качество дороги {@code routes.road_quality}, % — ВЫЧИТАЕТСЯ ({@code null} — 0)
 */
public record RouteCoefRef(
        Long winterCoefId,
        Long mountainCoefValue,
        Long inCityCoefValue,
        Integer stationCoef,
        Integer roadQuality
) {
}
