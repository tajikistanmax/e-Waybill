package tj.mintrans.epd.waybill.calc.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Самодостаточный вход расчёта грузового путевого листа (формы 2-Б, 5Б-БМ).
 *
 * <p>Ветка расчёта выбирается по первой цифре кода марки {@code brandNumber}
 * ({@code 1/2/4} — бортовой, {@code 3} — самосвал, {@code 5} — спецтехника,
 * {@code 8} — спецтехника в движении, {@code 9} — не нормируется). Наличие прицепа —
 * по пятому символу кода (цифра > 0). Направление ({@code directionMountainCoefId} и т.п.) —
 * КЛЮЧИ справочников (эталон §3.4).</p>
 */
public record CargoCalcInput(
        String brandName,
        String brandNumber,
        LocalDate vehicleYearManufacture,
        Long directionWinterCoefId,
        Long directionMountainCoefId,
        Long directionInCityCoefId,
        boolean applyCoefficient,      // false для формы 5Б-БМ (коэффициент = 1)
        Long odometerExit,
        Long odometerEntry,
        double transportWork,          // P, т·км
        double trips,                  // Z, ездок
        double specialWorkHours,
        double specialDistance,        // L1, км (спецтехника в движении)
        double trailerWeight,          // weight_ydak, т
        double trailerCarrying,        // carrying_ydak, т
        double trailerWeight2,         // weight_ydak_2, т (только 5Б-БМ)
        LocalDate calcDate,
        List<CalcFuelLine> fuels,
        BigDecimal earning,
        Double companyPercentIncome,
        Integer driverDegree,
        Short companyCat1,
        Short companyCat2,
        Short companyCat3
) {

    public CargoCalcInput {
        fuels = fuels == null ? List.of() : List.copyOf(fuels);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Строитель. */
    public static final class Builder {
        private String brandName;
        private String brandNumber;
        private LocalDate vehicleYearManufacture;
        private Long directionWinterCoefId;
        private Long directionMountainCoefId;
        private Long directionInCityCoefId;
        private boolean applyCoefficient = true;
        private Long odometerExit;
        private Long odometerEntry;
        private double transportWork;
        private double trips;
        private double specialWorkHours;
        private double specialDistance;
        private double trailerWeight;
        private double trailerCarrying;
        private double trailerWeight2;
        private LocalDate calcDate;
        private List<CalcFuelLine> fuels = new ArrayList<>();
        private BigDecimal earning;
        private Double companyPercentIncome;
        private Integer driverDegree;
        private Short companyCat1;
        private Short companyCat2;
        private Short companyCat3;

        public Builder brandName(String v) { this.brandName = v; return this; }
        public Builder brandNumber(String v) { this.brandNumber = v; return this; }
        public Builder vehicleYearManufacture(LocalDate v) { this.vehicleYearManufacture = v; return this; }
        public Builder directionWinterCoefId(Long v) { this.directionWinterCoefId = v; return this; }
        public Builder directionMountainCoefId(Long v) { this.directionMountainCoefId = v; return this; }
        public Builder directionInCityCoefId(Long v) { this.directionInCityCoefId = v; return this; }
        public Builder applyCoefficient(boolean v) { this.applyCoefficient = v; return this; }
        public Builder odometerExit(Long v) { this.odometerExit = v; return this; }
        public Builder odometerEntry(Long v) { this.odometerEntry = v; return this; }
        public Builder transportWork(double v) { this.transportWork = v; return this; }
        public Builder trips(double v) { this.trips = v; return this; }
        public Builder specialWorkHours(double v) { this.specialWorkHours = v; return this; }
        public Builder specialDistance(double v) { this.specialDistance = v; return this; }
        public Builder trailerWeight(double v) { this.trailerWeight = v; return this; }
        public Builder trailerCarrying(double v) { this.trailerCarrying = v; return this; }
        public Builder trailerWeight2(double v) { this.trailerWeight2 = v; return this; }
        public Builder calcDate(LocalDate v) { this.calcDate = v; return this; }
        public Builder fuels(List<CalcFuelLine> v) { this.fuels = v; return this; }
        public Builder addFuel(CalcFuelLine v) { this.fuels.add(v); return this; }
        public Builder earning(BigDecimal v) { this.earning = v; return this; }
        public Builder companyPercentIncome(Double v) { this.companyPercentIncome = v; return this; }
        public Builder driverDegree(Integer v) { this.driverDegree = v; return this; }
        public Builder companyCat1(Short v) { this.companyCat1 = v; return this; }
        public Builder companyCat2(Short v) { this.companyCat2 = v; return this; }
        public Builder companyCat3(Short v) { this.companyCat3 = v; return this; }

        public CargoCalcInput build() {
            return new CargoCalcInput(brandName, brandNumber, vehicleYearManufacture,
                    directionWinterCoefId, directionMountainCoefId, directionInCityCoefId, applyCoefficient,
                    odometerExit, odometerEntry, transportWork, trips, specialWorkHours, specialDistance,
                    trailerWeight, trailerCarrying, trailerWeight2, calcDate, fuels, earning,
                    companyPercentIncome, driverDegree, companyCat1, companyCat2, companyCat3);
        }
    }
}
