package tj.mintrans.epd.waybill.calc.model;

/**
 * Вход расчёта нормативного расхода топлива по одному виду топлива (пассажирская ветка).
 *
 * <p>Эталон: docs/spec/07-calculations.md §1.5, {@code helpers.php:294, fuel_calc_100()}.</p>
 *
 * @param fuelId                 вид топлива (1/2/3)
 * @param baseNorm100            базовая норма л/100 км из {@code brands.fuel_100[*].consumption}
 * @param distanceKm             общий пробег {@code gashti_umumi}, км
 * @param excludingCoef          {@code routes.excluding_coef} — «душанбинская» ветка A без коэффициентов
 * @param additionalFuel100      {@code routes.additional_fuel_100}, л/100 км
 * @param additionalFuel         {@code routes.additional_fuel}, л/100 км (только ветка A)
 * @param routeCondFuel          {@code routes.cond_fuel}, л (только ветка A)
 * @param routeHeatingFuel       {@code routes.heating_fuel}, л (только ветка A)
 * @param coefficientMultiplier  множитель {@code (1 + 0.01*K)} (только ветка B)
 * @param conditionerHours       время работы кондиционера, ч
 * @param workHours              время работы, ч
 * @param airConditionerPercent  {@code parkings.air_conditioner} — надбавка на кондиционер, %
 * @param interiorHeatingPerHour {@code brands.fuel_interior_heating} — расход на отопление салона, л/ч
 * @param specialWorkFuel        надбавка на спецработу, л
 */
public record FuelNormRequest(
        long fuelId,
        double baseNorm100,
        double distanceKm,
        boolean excludingCoef,
        double additionalFuel100,
        double additionalFuel,
        double routeCondFuel,
        double routeHeatingFuel,
        double coefficientMultiplier,
        double conditionerHours,
        double workHours,
        int airConditionerPercent,
        double interiorHeatingPerHour,
        double specialWorkFuel
) {

    /**
     * Новый строитель запроса.
     *
     * @return строитель с нейтральными значениями по умолчанию
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Строитель {@link FuelNormRequest} — у записи 14 полей, позиционный конструктор нечитаем. */
    public static final class Builder {

        private long fuelId = 1L;
        private double baseNorm100;
        private double distanceKm;
        private boolean excludingCoef;
        private double additionalFuel100;
        private double additionalFuel;
        private double routeCondFuel;
        private double routeHeatingFuel;
        private double coefficientMultiplier = 1d;
        private double conditionerHours;
        private double workHours;
        private int airConditionerPercent;
        private double interiorHeatingPerHour;
        private double specialWorkFuel;

        private Builder() {
        }

        public Builder fuelId(long value) {
            this.fuelId = value;
            return this;
        }

        public Builder baseNorm100(double value) {
            this.baseNorm100 = value;
            return this;
        }

        public Builder distanceKm(double value) {
            this.distanceKm = value;
            return this;
        }

        public Builder excludingCoef(boolean value) {
            this.excludingCoef = value;
            return this;
        }

        public Builder additionalFuel100(double value) {
            this.additionalFuel100 = value;
            return this;
        }

        public Builder additionalFuel(double value) {
            this.additionalFuel = value;
            return this;
        }

        public Builder routeCondFuel(double value) {
            this.routeCondFuel = value;
            return this;
        }

        public Builder routeHeatingFuel(double value) {
            this.routeHeatingFuel = value;
            return this;
        }

        public Builder coefficientMultiplier(double value) {
            this.coefficientMultiplier = value;
            return this;
        }

        public Builder conditionerHours(double value) {
            this.conditionerHours = value;
            return this;
        }

        public Builder workHours(double value) {
            this.workHours = value;
            return this;
        }

        public Builder airConditionerPercent(int value) {
            this.airConditionerPercent = value;
            return this;
        }

        public Builder interiorHeatingPerHour(double value) {
            this.interiorHeatingPerHour = value;
            return this;
        }

        public Builder specialWorkFuel(double value) {
            this.specialWorkFuel = value;
            return this;
        }

        public FuelNormRequest build() {
            return new FuelNormRequest(fuelId, baseNorm100, distanceKm, excludingCoef,
                    additionalFuel100, additionalFuel, routeCondFuel, routeHeatingFuel,
                    coefficientMultiplier, conditionerHours, workHours, airConditionerPercent,
                    interiorHeatingPerHour, specialWorkFuel);
        }
    }
}
