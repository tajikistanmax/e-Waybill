package tj.mintrans.epd.waybill.calc.model;

/**
 * Нормативы расхода топлива марки ТС — три JSON-массива из справочника марок
 * ({@code brands.fuel_100}, {@code brands.fuel_100_dushanbe}, {@code brands.fuel_hour}).
 *
 * <p>В rohkhat-v2 эти поля читались напрямую из сущности {@code Brand}; здесь вынесены
 * в отдельную запись, чтобы расчётное ядро не зависело от модели справочника.</p>
 *
 * @param brandId              идентификатор марки (для сообщений в журнал)
 * @param fuel100              JSON-массив нормативов «л/100 км»
 * @param fuel100Dushanbe      JSON-массив нормативов для маршрутов {@code excluding_coef}
 * @param fuelHour             JSON-массив почасовых нормативов «л/час»
 * @param interiorHeatingPerHour расход на отопление салона, л/ч ({@code brands.fuel_interior_heating})
 */
public record BrandNorms(
        Long brandId,
        String fuel100,
        String fuel100Dushanbe,
        String fuelHour,
        double interiorHeatingPerHour
) {

    /** Марка без нормативов (расчёт вернёт пустой список / нули). */
    public static BrandNorms empty(Long brandId) {
        return new BrandNorms(brandId, null, null, null, 0d);
    }
}
