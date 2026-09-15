package tj.mintrans.epd.waybill.calc.model;

/**
 * Разложение сводного коэффициента расхода топлива на составляющие.
 *
 * <p>Итоговая формула (обе ветки, эталон docs/spec/07-calculations.md §1.4 и §3.4):</p>
 * <pre>
 * K = (winter + mountain + station + city + used) - roadQuality
 * multiplier = K != 0 ? 1 + 0.01 * K : 1
 * </pre>
 *
 * <p>Эталон: {@code helpers.php:137, getCoef()}; {@code CargoFuelBase.php:147, getCoef()}.</p>
 *
 * @param winterCoef   зимний коэффициент ({@code fuel_winter_coef.coef})
 * @param mountainCoef горный коэффициент ({@code mountain_coef.coef})
 * @param stationCoef  остановочный коэффициент ({@code routes.station_coef}); только пассажирская ветка
 * @param cityCoef     городской коэффициент ({@code city_coef.coef})
 * @param usedCoef     коэффициент износа ({@code used_coef.coef})
 * @param roadQuality  качество дороги ({@code routes.road_quality}), ВЫЧИТАЕТСЯ; только пассажирская ветка
 * @param k            суммарная надбавка в процентах
 * @param multiplier   итоговый множитель нормы расхода
 */
public record CoefficientBreakdown(
        int winterCoef,
        int mountainCoef,
        int stationCoef,
        int cityCoef,
        int usedCoef,
        int roadQuality,
        int k,
        double multiplier
) {

    /**
     * Сборка разложения с вычислением {@code K} и множителя.
     *
     * @param winterCoef   зимний коэффициент
     * @param mountainCoef горный коэффициент
     * @param stationCoef  остановочный коэффициент
     * @param cityCoef     городской коэффициент
     * @param usedCoef     коэффициент износа
     * @param roadQuality  качество дороги (вычитается)
     * @return разложение коэффициента
     */
    public static CoefficientBreakdown of(int winterCoef, int mountainCoef, int stationCoef,
                                          int cityCoef, int usedCoef, int roadQuality) {
        int k = (winterCoef + mountainCoef + stationCoef + cityCoef + usedCoef) - roadQuality;
        double multiplier = k != 0 ? 1 + 0.01 * k : 1d;
        return new CoefficientBreakdown(winterCoef, mountainCoef, stationCoef, cityCoef,
                usedCoef, roadQuality, k, multiplier);
    }

    /**
     * Нейтральное разложение — множитель 1 (маршруты с {@code routes.excluding_coef = true},
     * а также форма 5Б-БМ, где коэффициенты не применяются вовсе).
     *
     * @return разложение с нулевыми составляющими и множителем 1
     */
    public static CoefficientBreakdown neutral() {
        return new CoefficientBreakdown(0, 0, 0, 0, 0, 0, 0, 1d);
    }
}
