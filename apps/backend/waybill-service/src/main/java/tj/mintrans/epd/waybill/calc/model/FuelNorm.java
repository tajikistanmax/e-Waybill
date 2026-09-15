package tj.mintrans.epd.waybill.calc.model;

/**
 * Строка нормативов расхода топлива марки ТС — одна запись JSON-массива
 * {@code brands.fuel_100} / {@code brands.fuel_100_dushanbe} / {@code brands.fuel_hour}.
 *
 * <p>Эталон: docs/spec/07-calculations.md §3.1, {@code CargoFuelBase.php:18, calcFuel()}.</p>
 *
 * @param fuelId                вид топлива: 1 = бензин, 2 = дизель, 3 = газ
 * @param consumption           базовая норма, л/100 км ({@code m_base_100}); для {@code fuel_hour} — л/час
 * @param tonFor100             расход на 1 т груза на 100 км ({@code m_tkm})
 * @param sForRais              расход на 1 рейс ({@code s_for_rais})
 * @param workForHour           расход на 1 час работы оборудования ({@code work_for_hour})
 * @param consumptionForSpecial норма для спецработы в движении ({@code consumption_for_special})
 */
public record FuelNorm(
        long fuelId,
        double consumption,
        double tonFor100,
        double sForRais,
        double workForHour,
        double consumptionForSpecial
) {

    /**
     * Норматив только с базовым расходом.
     *
     * @param fuelId      вид топлива
     * @param consumption базовая норма, л/100 км
     * @return норматив с нулевыми прочими составляющими
     */
    public static FuelNorm ofBase(long fuelId, double consumption) {
        return new FuelNorm(fuelId, consumption, 0d, 0d, 0d, 0d);
    }
}
