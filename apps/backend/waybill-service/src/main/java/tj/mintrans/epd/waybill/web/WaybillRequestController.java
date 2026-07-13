package tj.mintrans.epd.waybill.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tj.mintrans.epd.waybill.domain.WaybillRequest;
import tj.mintrans.epd.waybill.domain.WaybillType;
import tj.mintrans.epd.waybill.service.WaybillRequestService;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Заявки на путевой лист. Водитель подаёт/смотрит/отменяет свои; диспетчер своей организации —
 * видит очередь, исправляет, одобряет (→ создаётся ПЛ) или отклоняет.
 */
@RestController
@RequestMapping("/api/v1/waybill-requests")
public class WaybillRequestController {

    private final WaybillRequestService service;

    public WaybillRequestController(WaybillRequestService service) {
        this.service = service;
    }

    public record CreateRequest(
            @NotNull WaybillType waybillType,
            @NotBlank String vehicleRegNumber,
            LocalDate requestedFrom,
            Integer odometer,
            String communicationType,
            String route,
            String schedule,
            String notes) {
    }

    public record EditRequest(
            WaybillType waybillType,
            String vehicleRegNumber,
            LocalDate requestedFrom,
            Integer odometer,
            String communicationType,
            String route,
            String schedule,
            String notes) {
    }

    public record ApproveRequest(String communicationType, Map<String, Object> typeData) {
    }

    public record RejectRequest(@NotBlank String reason) {
    }

    // ------------------------------------------------------------- водитель

    /** Водитель подаёт заявку на путевой лист (личность — из токена; госномер вписывает вручную). */
    @PostMapping
    @PreAuthorize("hasAnyRole('DRIVER','SYSTEM_ADMIN')")
    public ResponseEntity<WaybillRequest> create(@Valid @RequestBody CreateRequest req) {
        var saved = service.create(req.waybillType(), req.vehicleRegNumber(), req.requestedFrom(),
                req.odometer(), req.communicationType(), req.route(), req.schedule(), req.notes());
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    /** Заявки текущего водителя (свои). */
    @GetMapping("/mine")
    @PreAuthorize("hasAnyRole('DRIVER','SYSTEM_ADMIN')")
    public List<WaybillRequest> mine() {
        return service.listForDriver();
    }

    /** Водитель отменяет свою заявку (пока «Ожидает»). */
    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('DRIVER','SYSTEM_ADMIN')")
    public WaybillRequest cancel(@PathVariable UUID id) {
        return service.cancelOwn(id);
    }

    // ------------------------------------------------------------- диспетчер

    /** Очередь заявок организации диспетчера (по умолчанию — все; ?status=PENDING — только ожидающие). */
    @GetMapping
    @PreAuthorize("hasAnyRole('DISPATCHER','COMPANY_ADMIN','SYSTEM_ADMIN')")
    public List<WaybillRequest> list(@RequestParam(required = false) String status) {
        return service.listForOrganization(status);
    }

    /** Диспетчер исправляет поля заявки. */
    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('DISPATCHER','COMPANY_ADMIN','SYSTEM_ADMIN')")
    public WaybillRequest edit(@PathVariable UUID id, @RequestBody EditRequest req) {
        return service.edit(id, req.waybillType(), req.vehicleRegNumber(), req.requestedFrom(),
                req.odometer(), req.communicationType(), req.route(), req.schedule(), req.notes());
    }

    /** Диспетчер одобряет заявку → создаётся путевой лист (Т1). */
    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('DISPATCHER','SYSTEM_ADMIN')")
    public WaybillRequest approve(@PathVariable UUID id, @RequestBody(required = false) ApproveRequest req) {
        return service.approve(id,
                req == null ? null : req.communicationType(),
                req == null ? null : req.typeData());
    }

    /** Диспетчер отклоняет заявку с причиной. */
    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAnyRole('DISPATCHER','SYSTEM_ADMIN')")
    public WaybillRequest reject(@PathVariable UUID id, @Valid @RequestBody RejectRequest req) {
        return service.reject(id, req.reason());
    }
}
