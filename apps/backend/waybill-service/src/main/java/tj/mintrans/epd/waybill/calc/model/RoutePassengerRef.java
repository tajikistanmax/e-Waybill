package tj.mintrans.epd.waybill.calc.model;

/**
 * Путевые поля маршрута, нужные расчёту пассажирских показателей (перенос части
 * {@code routes} из ИС «Роҳхат», §2.1–§2.3).
 *
 * @param distanceA             длина направления «туда», км
 * @param distanceB             длина направления «обратно», км
 * @param coeUseCapacity        коэффициент использования вместимости
 * @param averageLengthPassSeat средняя дальность поездки пассажира, км (делитель; 0 → 1)
 * @param plannedLap            плановое число кругов за день
 * @param beginPathA            нулевой пробег «Гашти А», км (ЗНАЧЕНИЕ)
 * @param beginPathB            нулевой пробег «Гашти Б», км (ЗНАЧЕНИЕ)
 */
public record RoutePassengerRef(
        Double distanceA,
        Double distanceB,
        Double coeUseCapacity,
        Double averageLengthPassSeat,
        Integer plannedLap,
        Double beginPathA,
        Double beginPathB
) {
}
