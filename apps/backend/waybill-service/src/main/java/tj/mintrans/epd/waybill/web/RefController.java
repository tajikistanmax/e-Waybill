package tj.mintrans.epd.waybill.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tj.mintrans.epd.waybill.service.RefChannelRules.Form;
import tj.mintrans.epd.waybill.service.RefChannelService;
import tj.mintrans.epd.waybill.web.error.ApiErrors.NotFoundException;

import java.util.Map;
import java.util.UUID;

/**
 * B2B-канал перевозчиков (MIGRATION.md 9.8 / 9.2 — legacy {@code /api/ref/*}, {@code company.jwt:1}):
 * {@code apiResource waybill1ad|waybill1ade|waybill1a|waybill3c|waybill2b|waybill5bbm}, {@code waybill/confirm},
 * {@code remain_fuel}. Доступ — сервисный аккаунт интегратора (client-credentials, роль API_INTEGRATOR), как у
 * агрегаторов. Тела и ответы — snake_case legacy.
 */
@RestController
@RequestMapping("/api/v1/ref")
@PreAuthorize("hasAnyRole('API_INTEGRATOR','SYSTEM_ADMIN')")
public class RefController {

    private final RefChannelService service;

    public RefController(RefChannelService service) {
        this.service = service;
    }

    private static Form form(String key) {
        Form f = Form.parse(key);
        if (f == null) {
            throw new NotFoundException("Unknown form: " + key);
        }
        return f;
    }

    /**
     * {@code GET ref/waybills?type=waybill3c} — действующие листы формы (legacy {@code ApiDataController::waybills};
     * сверка 25.09, G1). Раньше запрос попадал в шаблон {@code /{form}} и отвечал «Unknown form».
     */
    @GetMapping("/waybills")
    public Map<String, Object> activeWaybills(@RequestParam(required = false) String type) {
        return service.activeWaybills(type);
    }

    /** {@code GET ref/{form}} — список ПЛ формы с фильтрами legacy (organization_rma, driver_rma, transport_registration_number, employee_rma, date_from/date_to, page, per_page). */
    @GetMapping("/{form:waybill[a-z0-9]+}")
    public RefChannelService.PageResult index(@PathVariable String form, @RequestParam Map<String, String> params) {
        return service.list(form(form), params);
    }

    /** {@code POST ref/{form}} — оформление ПЛ системой перевозчика. */
    @PostMapping("/{form:waybill[a-z0-9]+}")
    public ResponseEntity<RefChannelService.View> store(@PathVariable String form, @RequestBody Map<String, Object> body) {
        var wb = service.create(form(form), body);
        return ResponseEntity.status(HttpStatus.CREATED).body(RefChannelService.view(wb));
    }

    @GetMapping("/{form:waybill[a-z0-9]+}/{id}")
    public RefChannelService.View show(@PathVariable String form, @PathVariable UUID id) {
        return RefChannelService.view(service.get(form(form), id));
    }

    /** {@code PUT ref/{form}/{id}} — правка полей; с {@code indication_counter_entry} — закрытие (возврат). */
    @PutMapping("/{form:waybill[a-z0-9]+}/{id}")
    public RefChannelService.View update(@PathVariable String form, @PathVariable UUID id, @RequestBody Map<String, Object> body) {
        return RefChannelService.view(service.update(form(form), id, body));
    }

    /** {@code POST ref/waybill/confirm} — подтверждение врача/механика по РМА; ответ legacy — {@code true}. */
    @PostMapping("/waybill/confirm")
    public ResponseEntity<Boolean> confirm(@RequestBody Map<String, Object> body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.confirm(body));
    }

    /** {@code GET ref/remain_fuel} — остаток топлива по ТС для новой формы. */
    @GetMapping("/remain_fuel")
    public Map<String, Object> remainFuel(@RequestParam("transport_registration_number") String transportRegistrationNumber,
                                          @RequestParam(value = "waybill_type", required = false) String waybillType,
                                          @RequestParam(value = "fuel_id", required = false) Integer fuelId) {
        return service.remainFuel(transportRegistrationNumber, waybillType, fuelId);
    }
}
