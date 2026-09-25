package tj.mintrans.epd.waybill.calc.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Самодостаточный вход расчёта пассажирского путевого листа (формы 1-А, 1-АД, 1-АДЕ, 3-С).
 *
 * <p>Не зависит от сущностей: оркестратор {@code WaybillCalcEngine} собирает его из снимков
 * ПЛ, справочников марок и маршрута. Все поля коэффициентов маршрута — как в
 * {@link RouteCoefRef} ({@code mountainCoefValue}/{@code inCityCoefValue} — ЗНАЧЕНИЯ).</p>
 */
public record PassengerCalcInput(
        // --- ТС ---
        String brandName,
        LocalDate vehicleYearManufacture,
        int airConditionerPercent,
        Integer capacity,
        // --- маршрут: коэффициенты ---
        Long routeWinterCoefId,
        Long routeMountainCoefValue,
        Long routeInCityCoefValue,
        Integer routeStationCoef,
        Integer routeRoadQuality,
        boolean routeExcludingCoef,
        Double routeAdditionalFuel100,
        Double routeAdditionalFuel,
        Double routeCondFuel,
        Double routeHeatingFuel,
        // --- маршрут: путевые показатели ---
        Double routeDistanceA,
        Double routeDistanceB,
        Double routeBeginPathA,
        Double routeBeginPathB,
        Integer routePlannedLap,
        Double routeCoeUseCapacity,
        Double routeAverageLengthPassSeat,
        // --- рейс ---
        Long odometerExit,
        Long odometerEntry,
        int workTimeMinutes,
        double conditionerHours,
        long numberLap,
        int workDays,
        boolean speedometerTotalDistance,   // форма 1-АД г. Душанбе: общий пробег по спидометру
        LocalDate calcDate,
        List<CalcFuelLine> fuels,
        // --- деньги ---
        BigDecimal earning,
        BigDecimal kassa,
        Double companyPercentIncome,
        Integer driverDegree,
        Short companyCat1,
        Short companyCat2,
        Short companyCat3,
        // --- тариф маршрута ---
        Double tariffPricePer1Mkm,
        Double tariffPriceOneTime,
        Double brandCostServices,
        // Норма топлива от «гашти ҳамагӣ» (пробег по маршруту + нулевой), а не от одометра —
        // автобус 1-АД вне Душанбе (legacy BusBaseCalc: fuel_calc_100($row, $l_main)).
        boolean fuelByRouteRun
) {

    public PassengerCalcInput {
        fuels = fuels == null ? List.of() : List.copyOf(fuels);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Строитель — у записи много полей, позиционный конструктор нечитаем. */
    public static final class Builder {
        private String brandName;
        private LocalDate vehicleYearManufacture;
        private int airConditionerPercent;
        private Integer capacity;
        private Long routeWinterCoefId;
        private Long routeMountainCoefValue;
        private Long routeInCityCoefValue;
        private Integer routeStationCoef;
        private Integer routeRoadQuality;
        private boolean routeExcludingCoef;
        private Double routeAdditionalFuel100;
        private Double routeAdditionalFuel;
        private Double routeCondFuel;
        private Double routeHeatingFuel;
        private Double routeDistanceA;
        private Double routeDistanceB;
        private Double routeBeginPathA;
        private Double routeBeginPathB;
        private Integer routePlannedLap;
        private Double routeCoeUseCapacity;
        private Double routeAverageLengthPassSeat;
        private Long odometerExit;
        private Long odometerEntry;
        private int workTimeMinutes;
        private double conditionerHours;
        private long numberLap;
        private int workDays = 1;
        private boolean speedometerTotalDistance;
        private LocalDate calcDate;
        private List<CalcFuelLine> fuels = new ArrayList<>();
        private BigDecimal earning;
        private BigDecimal kassa;
        private Double companyPercentIncome;
        private Integer driverDegree;
        private Short companyCat1;
        private Short companyCat2;
        private Short companyCat3;
        private Double tariffPricePer1Mkm;
        private Double tariffPriceOneTime;
        private Double brandCostServices;
        private boolean fuelByRouteRun;

        public Builder brandName(String v) { this.brandName = v; return this; }
        public Builder vehicleYearManufacture(LocalDate v) { this.vehicleYearManufacture = v; return this; }
        public Builder airConditionerPercent(int v) { this.airConditionerPercent = v; return this; }
        public Builder capacity(Integer v) { this.capacity = v; return this; }
        public Builder routeWinterCoefId(Long v) { this.routeWinterCoefId = v; return this; }
        public Builder routeMountainCoefValue(Long v) { this.routeMountainCoefValue = v; return this; }
        public Builder routeInCityCoefValue(Long v) { this.routeInCityCoefValue = v; return this; }
        public Builder routeStationCoef(Integer v) { this.routeStationCoef = v; return this; }
        public Builder routeRoadQuality(Integer v) { this.routeRoadQuality = v; return this; }
        public Builder routeExcludingCoef(boolean v) { this.routeExcludingCoef = v; return this; }
        public Builder routeAdditionalFuel100(Double v) { this.routeAdditionalFuel100 = v; return this; }
        public Builder routeAdditionalFuel(Double v) { this.routeAdditionalFuel = v; return this; }
        public Builder routeCondFuel(Double v) { this.routeCondFuel = v; return this; }
        public Builder routeHeatingFuel(Double v) { this.routeHeatingFuel = v; return this; }
        public Builder routeDistanceA(Double v) { this.routeDistanceA = v; return this; }
        public Builder routeDistanceB(Double v) { this.routeDistanceB = v; return this; }
        public Builder routeBeginPathA(Double v) { this.routeBeginPathA = v; return this; }
        public Builder routeBeginPathB(Double v) { this.routeBeginPathB = v; return this; }
        public Builder routePlannedLap(Integer v) { this.routePlannedLap = v; return this; }
        public Builder routeCoeUseCapacity(Double v) { this.routeCoeUseCapacity = v; return this; }
        public Builder routeAverageLengthPassSeat(Double v) { this.routeAverageLengthPassSeat = v; return this; }
        public Builder odometerExit(Long v) { this.odometerExit = v; return this; }
        public Builder odometerEntry(Long v) { this.odometerEntry = v; return this; }
        public Builder workTimeMinutes(int v) { this.workTimeMinutes = v; return this; }
        public Builder conditionerHours(double v) { this.conditionerHours = v; return this; }
        public Builder numberLap(long v) { this.numberLap = v; return this; }
        public Builder workDays(int v) { this.workDays = v; return this; }
        public Builder speedometerTotalDistance(boolean v) { this.speedometerTotalDistance = v; return this; }
        public Builder calcDate(LocalDate v) { this.calcDate = v; return this; }
        public Builder fuels(List<CalcFuelLine> v) { this.fuels = v; return this; }
        public Builder addFuel(CalcFuelLine v) { this.fuels.add(v); return this; }
        public Builder earning(BigDecimal v) { this.earning = v; return this; }
        public Builder kassa(BigDecimal v) { this.kassa = v; return this; }
        public Builder companyPercentIncome(Double v) { this.companyPercentIncome = v; return this; }
        public Builder driverDegree(Integer v) { this.driverDegree = v; return this; }
        public Builder companyCat1(Short v) { this.companyCat1 = v; return this; }
        public Builder companyCat2(Short v) { this.companyCat2 = v; return this; }
        public Builder companyCat3(Short v) { this.companyCat3 = v; return this; }
        public Builder tariffPricePer1Mkm(Double v) { this.tariffPricePer1Mkm = v; return this; }
        public Builder tariffPriceOneTime(Double v) { this.tariffPriceOneTime = v; return this; }
        public Builder brandCostServices(Double v) { this.brandCostServices = v; return this; }
        public Builder fuelByRouteRun(boolean v) { this.fuelByRouteRun = v; return this; }

        public PassengerCalcInput build() {
            return new PassengerCalcInput(brandName, vehicleYearManufacture, airConditionerPercent, capacity,
                    routeWinterCoefId, routeMountainCoefValue, routeInCityCoefValue, routeStationCoef,
                    routeRoadQuality, routeExcludingCoef, routeAdditionalFuel100, routeAdditionalFuel,
                    routeCondFuel, routeHeatingFuel, routeDistanceA, routeDistanceB, routeBeginPathA,
                    routeBeginPathB, routePlannedLap, routeCoeUseCapacity, routeAverageLengthPassSeat,
                    odometerExit, odometerEntry, workTimeMinutes, conditionerHours, numberLap, workDays,
                    speedometerTotalDistance, calcDate, fuels, earning, kassa, companyPercentIncome,
                    driverDegree, companyCat1, companyCat2, companyCat3, tariffPricePer1Mkm,
                    tariffPriceOneTime, brandCostServices, fuelByRouteRun);
        }
    }
}
