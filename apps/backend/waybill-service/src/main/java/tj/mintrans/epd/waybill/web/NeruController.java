package tj.mintrans.epd.waybill.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tj.mintrans.epd.waybill.domain.Waybill;
import tj.mintrans.epd.waybill.domain.WaybillStatus;
import tj.mintrans.epd.waybill.repository.WaybillRepository;
import tj.mintrans.epd.waybill.web.error.ApiErrors.NotFoundException;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.Map;

/**
 * Витрина Neru: read-эндпоинт для дорожных камер и постов ГАИ. По госномеру ТС
 * возвращает действующий путевой лист — для антифрод-сверки камерами системы Neru
 * (сопоставление номера с наличием оформленного ПЛ). Доступ ТОЛЬКО по токену
 * (сервисный аккаунт Neru / токен инспектора): госномер перечислим, поэтому
 * публичный доступ раскрыл бы ПДн водителей — в отличие от проверки QR, где нужен
 * подписанный токен из самого документа.
 */
@RestController
@RequestMapping("/api/v1/neru")
public class NeruController {

    private final WaybillRepository waybills;

    public NeruController(WaybillRepository waybills) {
        this.waybills = waybills;
    }

    /**
     * Действующий путевой лист по госномеру ТС. Среди возможных нескольких
     * (маловероятно — на ТС допустим один действующий ПЛ) возвращаем самый свежий.
     */
    @GetMapping("/active-by-plate")
    public NeruWaybillView activeByPlate(@RequestParam String plate) {
        String canonical = plate.trim().toUpperCase();
        return waybills.findByVehicleRegNumberAndStatusIn(canonical, WaybillStatus.OPEN_STATUSES).stream()
                .max(Comparator.comparing(Waybill::getCreatedAt))
                .map(NeruController::toView)
                .orElseThrow(() -> new NotFoundException(
                        "На госномер %s действующий путевой лист не найден".formatted(canonical)));
    }

    // ------------------------------------------------------------- маппинг снимков

    private static NeruWaybillView toView(Waybill wb) {
        var driver = wb.getDriverSnapshot();
        String driverName = driver == null ? null : str(driver.get("fullName"));
        return new NeruWaybillView(
                wb.getNumber(),
                wb.getStatus().name(),
                wb.getVehicleRegNumber(),
                driverName,
                wb.getOrganizationRma(),
                wb.getValidFrom(),
                wb.getValidTo());
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    /** Компактная витрина ПЛ для камер/постов Neru (только для сверки, без чувствительных данных). */
    public record NeruWaybillView(
            String number,
            String status,
            String vehicleRegNumber,
            String driverName,
            String organizationRma,
            OffsetDateTime validFrom,
            OffsetDateTime validTo) {
    }
}
