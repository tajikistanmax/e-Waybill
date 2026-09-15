package tj.mintrans.epd.waybill.calc.model;

/**
 * Вход расчёта расхода топлива грузового путевого листа (формы 2-Б и 5Б-БМ).
 *
 * <p>Эталон: docs/spec/07-calculations.md §3.5–§3.6,
 * {@code LabadorFuel.php}, {@code SelfUnloadFuel.php}, {@code SpecialFuel.php},
 * {@code SpecialMoverFuel.php}.</p>
 *
 * @param norm             нормативы марки по выбранному виду топлива
 * @param distanceKm       пробег за день {@code l_dist = entry - exit}, км
 * @param transportWork    транспортная работа {@code P}, т·км
 * @param trips            число ездок {@code Z}, шт.
 * @param coefficient      сводный коэффициент {@code coef}; для 5Б-БМ всегда 1
 * @param hasTrailer       наличие прицепа (5-я цифра кода марки {@code brands.number})
 * @param trailerWeight    масса прицепа {@code parkings.weight_ydak}, т
 * @param trailerCarrying  грузоподъёмность прицепа {@code parkings.carrying_ydak}, т
 * @param trailerWeight2   масса второго прицепа {@code parkings.weight_ydak_2}, т (только 5Б-БМ)
 * @param specialWorkHours время работы оборудования, ч
 * @param specialDistance  пробег при спецработе {@code L1}, км
 * @param vanSurcharge     надбавка «фуругон» +10 % (в оригинале недостижима, §3.5.1 [БАГ])
 */
public record CargoFuelRequest(
        FuelNorm norm,
        double distanceKm,
        double transportWork,
        double trips,
        double coefficient,
        boolean hasTrailer,
        double trailerWeight,
        double trailerCarrying,
        double trailerWeight2,
        double specialWorkHours,
        double specialDistance,
        boolean vanSurcharge
) {

    /**
     * Новый строитель запроса.
     *
     * @param norm нормативы марки по виду топлива
     * @return строитель с коэффициентом 1 и без прицепа
     */
    public static Builder builder(FuelNorm norm) {
        return new Builder(norm);
    }

    /** Строитель {@link CargoFuelRequest}. */
    public static final class Builder {

        private final FuelNorm norm;
        private double distanceKm;
        private double transportWork;
        private double trips;
        private double coefficient = 1d;
        private boolean hasTrailer;
        private double trailerWeight;
        private double trailerCarrying;
        private double trailerWeight2;
        private double specialWorkHours;
        private double specialDistance;
        private boolean vanSurcharge;

        private Builder(FuelNorm norm) {
            this.norm = norm;
        }

        public Builder distanceKm(double value) {
            this.distanceKm = value;
            return this;
        }

        public Builder transportWork(double value) {
            this.transportWork = value;
            return this;
        }

        public Builder trips(double value) {
            this.trips = value;
            return this;
        }

        public Builder coefficient(double value) {
            this.coefficient = value;
            return this;
        }

        public Builder hasTrailer(boolean value) {
            this.hasTrailer = value;
            return this;
        }

        public Builder trailerWeight(double value) {
            this.trailerWeight = value;
            return this;
        }

        public Builder trailerCarrying(double value) {
            this.trailerCarrying = value;
            return this;
        }

        public Builder trailerWeight2(double value) {
            this.trailerWeight2 = value;
            return this;
        }

        public Builder specialWorkHours(double value) {
            this.specialWorkHours = value;
            return this;
        }

        public Builder specialDistance(double value) {
            this.specialDistance = value;
            return this;
        }

        public Builder vanSurcharge(boolean value) {
            this.vanSurcharge = value;
            return this;
        }

        public CargoFuelRequest build() {
            return new CargoFuelRequest(norm, distanceKm, transportWork, trips, coefficient,
                    hasTrailer, trailerWeight, trailerCarrying, trailerWeight2,
                    specialWorkHours, specialDistance, vanSurcharge);
        }
    }
}
