package tj.mintrans.epd.waybill.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tj.mintrans.epd.waybill.service.FuelPrefillService;
import tj.mintrans.epd.waybill.service.FuelPrefillService.FuelPrefill;

import java.util.UUID;

/**
 * Подсказка для формы топлива из предыдущего ПЛ того же ТС (перенос legacy
 * {@code parking_fuel_left} / {@code parking_fuel_give} / {@code ref/remain_fuel}, MIGRATION.md §4.9).
 * Область доступа — как у карточки ПЛ ({@code WaybillService.get}: тенант видит свои ПЛ).
 */
@RestController
@Validated
@RequestMapping("/api/v1/waybills/{id}")
public class FuelPrefillController {

    private final FuelPrefillService service;

    public FuelPrefillController(FuelPrefillService service) {
        this.service = service;
    }

    @GetMapping("/fuel-prefill")
    @PreAuthorize("hasAnyRole('DISPATCHER','FUEL_STATION','COMPANY_ADMIN','BRANCH_ADMIN','ACCOUNTANT','SYSTEM_ADMIN')")
    public FuelPrefill prefill(@PathVariable UUID id,
                               @RequestParam @Min(1) @Max(5) int fuelType) {
        return service.forWaybill(id, (short) fuelType);
    }
}
