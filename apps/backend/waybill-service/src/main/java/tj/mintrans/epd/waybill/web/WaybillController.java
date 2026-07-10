package tj.mintrans.epd.waybill.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tj.mintrans.epd.waybill.config.CurrentUser;
import tj.mintrans.epd.waybill.domain.Waybill;
import tj.mintrans.epd.waybill.domain.WaybillStatus;
import tj.mintrans.epd.waybill.domain.WaybillStatusEvent;
import tj.mintrans.epd.waybill.domain.WaybillTitle;
import tj.mintrans.epd.waybill.domain.WaybillType;
import tj.mintrans.epd.waybill.repository.WaybillRepository;
import tj.mintrans.epd.waybill.repository.WaybillStatusEventRepository;
import tj.mintrans.epd.waybill.repository.WaybillTitleRepository;
import tj.mintrans.epd.waybill.service.FuelCalculationService;
import tj.mintrans.epd.waybill.service.QrTokenService;
import tj.mintrans.epd.waybill.service.WaybillService;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/waybills")
public class WaybillController {

    private final WaybillService service;
    private final WaybillRepository waybills;
    private final WaybillTitleRepository titles;
    private final WaybillStatusEventRepository events;
    private final QrTokenService qr;
    private final FuelCalculationService fuelCalculation;
    private final CurrentUser currentUser;

    public WaybillController(WaybillService service, WaybillRepository waybills,
                             WaybillTitleRepository titles, WaybillStatusEventRepository events,
                             QrTokenService qr, FuelCalculationService fuelCalculation,
                             CurrentUser currentUser) {
        this.service = service;
        this.waybills = waybills;
        this.titles = titles;
        this.events = events;
        this.qr = qr;
        this.fuelCalculation = fuelCalculation;
        this.currentUser = currentUser;
    }

    // ------------------------------------------------------------- запросы

    public record CreateRequest(
            @NotNull WaybillType waybillType,
            @NotBlank @Pattern(regexp = "\\d{9,10}") String organizationRma,
            @NotBlank String vehicleRegNumber,
            @NotBlank @Pattern(regexp = "\\d{9,10}") String driverRma,
            @Pattern(regexp = "\\d{9,10}") String secondDriverRma,
            String communicationType,
            String route,
            String schedule,
            String specialMark,
            Map<String, Object> typeData) {
    }

    public record SignT1Request(
            @NotBlank @Pattern(regexp = "\\d{9,10}") String dispatcherRma,
            OffsetDateTime validFrom,
            Integer validityDays) {
    }

    public record MedRequest(
            @NotBlank @Pattern(regexp = "\\d{9,10}") String employeeRma,
            @NotNull Boolean passed,
            Map<String, Object> indicators) {
    }

    public record TechRequest(
            @NotBlank @Pattern(regexp = "\\d{9,10}") String employeeRma,
            @NotNull Boolean passed,
            Map<String, Object> checklist) {
    }

    public record IssueRequest(String driverConfirmation) {
    }

    public record ActivateRequest(
            @NotBlank @Pattern(regexp = "\\d{9,10}") String dispatcherRma,
            Integer odometerExit) {
    }

    public record ReturnRequest(
            @NotBlank @Pattern(regexp = "\\d{9,10}") String dispatcherRma,
            @NotNull Integer odometerEntry) {
    }

    public record CloseRequest(String actor) {
    }

    public record CancelRequest(@NotBlank String reason, String actor) {
    }

    public record ReplaceDriverRequest(
            @NotBlank @Pattern(regexp = "\\d{9,10}") String newDriverRma,
            @NotBlank @Pattern(regexp = "\\d{9,10}") String dispatcherRma) {
    }

    public record ReplaceVehicleRequest(
            @NotBlank String newVehicleRegNumber,
            @NotBlank @Pattern(regexp = "\\d{9,10}") String dispatcherRma) {
    }

    public record BlockRequest(@NotBlank String reason, String actor) {
    }

    public record UnblockRequest(@NotBlank String reason, String actor) {
    }

    public record ConfirmPaymentRequest(String method, String externalRef, String actor) {
    }

    // ------------------------------------------------------------- жизненный цикл

    @PostMapping
    @PreAuthorize("hasAnyRole('DISPATCHER','SYSTEM_ADMIN')")
    public ResponseEntity<Waybill> create(@Valid @RequestBody CreateRequest req) {
        var wb = service.create(req.waybillType(), req.organizationRma(), req.vehicleRegNumber(),
                req.driverRma(), req.secondDriverRma(), req.communicationType(), req.route(),
                req.schedule(), req.specialMark(), req.typeData());
        return ResponseEntity.status(HttpStatus.CREATED).body(wb);
    }

    @PostMapping("/{id}/titles/t1")
    @PreAuthorize("hasAnyRole('DISPATCHER','SYSTEM_ADMIN')")
    public Waybill signT1(@PathVariable UUID id, @Valid @RequestBody SignT1Request req) {
        return service.signT1(id, req.dispatcherRma(), req.validFrom(), req.validityDays());
    }

    @PostMapping("/{id}/confirm-med")
    @PreAuthorize("hasAnyRole('DOCTOR','SYSTEM_ADMIN')")
    public Waybill confirmMed(@PathVariable UUID id, @Valid @RequestBody MedRequest req) {
        return service.confirmMed(id, req.employeeRma(), req.passed(), req.indicators());
    }

    @PostMapping("/{id}/confirm-tech")
    @PreAuthorize("hasAnyRole('MECHANIC','SYSTEM_ADMIN')")
    public Waybill confirmTech(@PathVariable UUID id, @Valid @RequestBody TechRequest req) {
        return service.confirmTech(id, req.employeeRma(), req.passed(), req.checklist());
    }

    @PostMapping("/{id}/issue")
    @PreAuthorize("hasAnyRole('DISPATCHER','SYSTEM_ADMIN')")
    public Waybill issue(@PathVariable UUID id, @RequestBody(required = false) IssueRequest req) {
        return service.issue(id, req == null ? null : req.driverConfirmation());
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAnyRole('DISPATCHER','SYSTEM_ADMIN')")
    public Waybill activate(@PathVariable UUID id, @Valid @RequestBody ActivateRequest req) {
        return service.activate(id, req.dispatcherRma(), req.odometerExit());
    }

    @PostMapping("/{id}/return")
    @PreAuthorize("hasAnyRole('DISPATCHER','SYSTEM_ADMIN')")
    public Waybill returnTrip(@PathVariable UUID id, @Valid @RequestBody ReturnRequest req) {
        return service.returnTrip(id, req.dispatcherRma(), req.odometerEntry());
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("hasAnyRole('DISPATCHER','SYSTEM_ADMIN')")
    public Waybill close(@PathVariable UUID id, @RequestBody(required = false) CloseRequest req) {
        return service.close(id, req == null ? "system" : req.actor());
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('DISPATCHER','SYSTEM_ADMIN')")
    public Waybill cancel(@PathVariable UUID id, @Valid @RequestBody CancelRequest req) {
        return service.cancel(id, req.reason(), req.actor());
    }

    /** Замена водителя после недопуска (MED_REJECTED → CREATED, титул CORRECTION). */
    @PostMapping("/{id}/replace-driver")
    @PreAuthorize("hasAnyRole('DISPATCHER','SYSTEM_ADMIN')")
    public Waybill replaceDriver(@PathVariable UUID id, @Valid @RequestBody ReplaceDriverRequest req) {
        return service.replaceDriver(id, req.newDriverRma(), req.dispatcherRma());
    }

    /** Замена ТС после отклонения техконтролем (TECH_REJECTED → CREATED, титул CORRECTION). */
    @PostMapping("/{id}/replace-vehicle")
    @PreAuthorize("hasAnyRole('DISPATCHER','SYSTEM_ADMIN')")
    public Waybill replaceVehicle(@PathVariable UUID id, @Valid @RequestBody ReplaceVehicleRequest req) {
        return service.replaceVehicle(id, req.newVehicleRegNumber(), req.dispatcherRma());
    }

    /** Блокировка инспектором при нарушении на дорожном контроле (ACTIVE → BLOCKED). */
    @PostMapping("/{id}/block")
    @PreAuthorize("hasAnyRole('INSPECTOR','SYSTEM_ADMIN')")
    public Waybill block(@PathVariable UUID id, @Valid @RequestBody BlockRequest req) {
        return service.block(id, req.reason(), req.actor() == null ? "inspector" : req.actor());
    }

    /** Разблокировка администратором Минтранса с обоснованием (BLOCKED → ACTIVE). */
    @PostMapping("/{id}/unblock")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public Waybill unblock(@PathVariable UUID id, @Valid @RequestBody UnblockRequest req) {
        return service.unblock(id, req.reason(), req.actor() == null ? "mintrans-admin" : req.actor());
    }

    // ------------------------------------------------------------- оплата

    /** Карточка оплаты (сумма, статус, реквизиты подтверждения). */
    @GetMapping("/{id}/payment")
    public tj.mintrans.epd.waybill.domain.WaybillPayment payment(@PathVariable UUID id) {
        return service.getPayment(id);
    }

    /**
     * Подтверждение оплаты бухгалтером/админом: AWAITING_PAYMENT → PAID → READY (номер + QR).
     * Платёжный шлюз (webhook) — этап 1б, будет вызывать этот же сервисный метод.
     */
    @PostMapping("/{id}/confirm-payment")
    @PreAuthorize("hasAnyRole('ACCOUNTANT','COMPANY_ADMIN','SYSTEM_ADMIN')")
    public Waybill confirmPayment(@PathVariable UUID id, @RequestBody(required = false) ConfirmPaymentRequest req) {
        return service.confirmPayment(id,
                req == null ? null : req.method(),
                req == null ? null : req.externalRef(),
                req == null || req.actor() == null ? "accountant" : req.actor());
    }

    // ------------------------------------------------------------- чтение

    @GetMapping("/{id}")
    public Waybill get(@PathVariable UUID id) {
        return service.get(id);
    }

    @GetMapping
    public List<Waybill> list(@RequestParam(required = false) String organizationRma,
                              @RequestParam(required = false) WaybillStatus status,
                              @RequestParam(required = false) String number) {
        // Мультиарендность: не-админ видит только свою организацию —
        // пришедший organizationRma игнорируется, берётся claim из токена.
        if (currentUser.isTenantScoped()) {
            var own = currentUser.organizationRma();
            if (own.isEmpty()) {
                return List.of();
            }
            organizationRma = own.get();
            if (number != null) {
                final String orgRma = organizationRma;
                return waybills.findByNumber(number)
                        .filter(wb -> orgRma.equals(wb.getOrganizationRma()))
                        .map(List::of).orElseGet(List::of);
            }
            var result = waybills.findByOrganizationRmaOrderByCreatedAtDesc(organizationRma);
            if (status != null) {
                final WaybillStatus st = status;
                result = result.stream().filter(wb -> wb.getStatus() == st).toList();
            }
            return result;
        }
        if (number != null) {
            return waybills.findByNumber(number).map(List::of).orElseGet(List::of);
        }
        if (organizationRma != null) {
            return waybills.findByOrganizationRmaOrderByCreatedAtDesc(organizationRma);
        }
        if (status != null) {
            return waybills.findByStatusOrderByCreatedAtDesc(status);
        }
        return waybills.findAll();
    }

    @GetMapping("/{id}/titles")
    public List<WaybillTitle> titles(@PathVariable UUID id) {
        service.get(id);
        return titles.findByWaybillIdOrderBySignedAt(id);
    }

    @GetMapping("/{id}/status-history")
    public List<WaybillStatusEvent> statusHistory(@PathVariable UUID id) {
        service.get(id);
        return events.findByWaybillIdOrderByCreatedAt(id);
    }

    /** Нормативный расход топлива и стоимость рейса (доступно после возврата, Т5). */
    @GetMapping("/{id}/fuel-calculation")
    public FuelCalculationService.FuelCalculation fuelCalculation(@PathVariable UUID id) {
        return fuelCalculation.calculate(service.get(id));
    }

    /** Подписанная QR-нагрузка (JWS) — её кодирует в QR мобильное приложение водителя. */
    @GetMapping("/{id}/qr")
    public Map<String, String> qr(@PathVariable UUID id) {
        var wb = service.get(id);
        if (wb.getNumber() == null) {
            throw new tj.mintrans.epd.waybill.web.error.ApiErrors.ConflictException(
                    "QR доступен после присвоения номера (статус READY и далее)");
        }
        return Map.of("jws", qr.sign(wb));
    }
}
