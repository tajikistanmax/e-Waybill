package tj.mintrans.epd.waybill.service;

import org.springframework.stereotype.Service;
import tj.mintrans.epd.waybill.client.MasterDataClient;
import tj.mintrans.epd.waybill.domain.FuelRecord;
import tj.mintrans.epd.waybill.domain.Waybill;
import tj.mintrans.epd.waybill.repository.FuelRecordRepository;
import tj.mintrans.epd.waybill.web.error.ApiErrors.UnprocessableException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Нормирование расхода топлива и расчёт стоимости рейса по справочникам
 * (нормы, коэффициенты, нархнома — spec/notes/02, раздел 3). Расчёт доступен
 * после возвращения (Т5), когда известен пробег.
 */
@Service
public class FuelCalculationService {

    private static final int SCALE = 2;

    private final MasterDataClient masterData;
    private final FuelRecordRepository fuelRecords;

    public FuelCalculationService(MasterDataClient masterData, FuelRecordRepository fuelRecords) {
        this.masterData = masterData;
        this.fuelRecords = fuelRecords;
    }

    /**
     * Результат расчёта расхода топлива по путевому листу.
     *
     * @param km                  пробег за рейс (одометр возврата − выезда)
     * @param baseNormPer100km    базовая норма расхода, л/100км
     * @param coefficientsApplied применённые коэффициенты (зимний/город/высокогорье)
     * @param normLiters          нормативный расход, л
     * @param factLiters          фактический расход (сумма выданного топлива), л
     * @param deviationLiters     отклонение факт − норма, л (перерасход > 0, экономия < 0)
     * @param tariffPerKm         тариф за 1 км, сомони (null — тариф не задан)
     * @param tripCost            стоимость рейса, сомони (null — тариф не задан)
     */
    public record FuelCalculation(
            int km,
            BigDecimal baseNormPer100km,
            List<Map<String, Object>> coefficientsApplied,
            BigDecimal normLiters,
            BigDecimal factLiters,
            BigDecimal deviationLiters,
            BigDecimal tariffPerKm,
            BigDecimal tripCost) {
    }

    public FuelCalculation calculate(Waybill wb) {
        if (wb.getOdometerEntry() == null) {
            throw new UnprocessableException("Расчёт доступен после возврата (Т5)");
        }
        int exit = wb.getOdometerExit() != null ? wb.getOdometerExit() : 0;
        int km = wb.getOdometerEntry() - exit;

        Map<String, Object> vehicle = wb.getVehicleSnapshot();
        int transportType = intValue(vehicle == null ? null : vehicle.get("transportType"));
        String brand = str(vehicle == null ? null : vehicle.get("brand"));

        // Норма: точное совпадение марки, иначе запись для всех марок (brand=NULL).
        List<Map<String, Object>> norms = masterData.listFuelNorms();
        Map<String, Object> norm = norms.stream()
                .filter(n -> transportType == intValue(n.get("transportType")))
                .filter(n -> !brand.isBlank() && brand.equalsIgnoreCase(str(n.get("brand"))))
                .findFirst()
                .orElseGet(() -> norms.stream()
                        .filter(n -> transportType == intValue(n.get("transportType")))
                        .filter(n -> n.get("brand") == null)
                        .findFirst()
                        .orElse(null));
        if (norm == null) {
            throw new UnprocessableException("Норма расхода для типа ТС не задана");
        }
        BigDecimal baseNorm = decimal(norm.get("baseNorm"));

        // Коэффициенты нормирования. Сезонный (WINTER) — по дате РЕЙСА (validFrom), а не по дате
        // расчёта: иначе норму можно исказить, посчитав рейс в другой сезон.
        int month = (wb.getValidFrom() != null ? wb.getValidFrom() : java.time.OffsetDateTime.now()).getMonthValue();
        Map<String, Object> org = wb.getOrganizationSnapshot();
        Integer orgRegionId = org == null ? null : intOrNull(org.get("regionId"));
        boolean urban = "URBAN".equals(wb.getCommunicationType());

        List<Map<String, Object>> applied = new ArrayList<>();
        BigDecimal factor = BigDecimal.ONE;
        for (Map<String, Object> c : masterData.listCoefficients()) {
            boolean apply = switch (str(c.get("kind"))) {
                case "WINTER" -> inMonthWindow(month, c.get("monthFrom"), c.get("monthTo"));
                case "CITY" -> urban;
                case "HIGHLAND" -> orgRegionId != null && orgRegionId.equals(intOrNull(c.get("regionId")));
                case "USAGE" -> false; // TODO: коэффициент износа по году/пробегу ТС
                default -> false;
            };
            if (apply) {
                applied.add(c);
                factor = factor.multiply(decimal(c.get("value")));
            }
        }

        // normLiters = baseNorm × km / 100 × произведение коэффициентов.
        BigDecimal normLiters = baseNorm
                .multiply(BigDecimal.valueOf(km))
                .multiply(factor)
                .divide(BigDecimal.valueOf(100), SCALE, RoundingMode.HALF_UP);

        List<FuelRecord> records = fuelRecords.findByWaybillIdOrderByCreatedAt(wb.getId());
        BigDecimal factLiters = records.stream()
                .map(FuelRecord::getFuelGiven)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(SCALE, RoundingMode.HALF_UP);

        BigDecimal deviationLiters = factLiters.subtract(normLiters).setScale(SCALE, RoundingMode.HALF_UP);

        // Тариф: по типу ТС и виду топлива, запись fuel_type=NULL как fallback.
        Short fuelType = records.stream().map(FuelRecord::getFuelType).findFirst().orElse(null);
        List<Map<String, Object>> tariffs = masterData.listTariffs();
        Map<String, Object> tariff = tariffs.stream()
                .filter(t -> transportType == intValue(t.get("transportType")))
                .filter(t -> fuelType != null && t.get("fuelType") != null
                        && fuelType.intValue() == intValue(t.get("fuelType")))
                .findFirst()
                .orElseGet(() -> tariffs.stream()
                        .filter(t -> transportType == intValue(t.get("transportType")))
                        .filter(t -> t.get("fuelType") == null)
                        .findFirst()
                        .orElse(null));

        BigDecimal tariffPerKm = null;
        BigDecimal tripCost = null;
        if (tariff != null) {
            tariffPerKm = decimal(tariff.get("pricePerKm"));
            tripCost = tariffPerKm.multiply(BigDecimal.valueOf(km)).setScale(SCALE, RoundingMode.HALF_UP);
        }

        return new FuelCalculation(km, baseNorm, applied, normLiters, factLiters,
                deviationLiters, tariffPerKm, tripCost);
    }

    /** WINTER: текущий месяц в окне from..to с учётом перехода через год (напр. 11..3). */
    private static boolean inMonthWindow(int month, Object from, Object to) {
        Integer f = intOrNull(from);
        Integer t = intOrNull(to);
        if (f == null || t == null) {
            return false;
        }
        return f <= t ? (month >= f && month <= t) : (month >= f || month <= t);
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static Integer intOrNull(Object o) {
        if (o == null) {
            return null;
        }
        return o instanceof Number n ? n.intValue() : Integer.valueOf(o.toString());
    }

    private static int intValue(Object o) {
        return o instanceof Number n ? n.intValue() : Integer.parseInt(str(o).isBlank() ? "0" : o.toString());
    }

    private static BigDecimal decimal(Object o) {
        if (o == null) {
            return null;
        }
        return o instanceof BigDecimal b ? b : new BigDecimal(o.toString());
    }
}
