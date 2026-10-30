package tj.mintrans.epd.masterdata.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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
import org.springframework.web.server.ResponseStatusException;
import tj.mintrans.epd.masterdata.config.CurrentUser;
import tj.mintrans.epd.masterdata.domain.BillingLedgerEntry;
import tj.mintrans.epd.masterdata.repository.OrganizationRepository;
import tj.mintrans.epd.masterdata.service.OrganizationBalanceService;
import tj.mintrans.epd.masterdata.web.error.NotFoundException;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Баланс организации по биллингу выдачи ПЛ (решение владельца 31.10.2026). Просмотр — сама
 * организация (своя) или SYSTEM_ADMIN (любая); пополнение — только SYSTEM_ADMIN, по факту
 * внешнего банковского перевода (как сейчас с лицензиями — платёж происходит вне платформы).
 */
@RestController
@RequestMapping("/api/v1/organizations/{rma}/balance")
public class OrganizationBalanceController {

    private final OrganizationBalanceService balances;
    private final OrganizationRepository organizations;
    private final CurrentUser currentUser;

    public OrganizationBalanceController(OrganizationBalanceService balances, OrganizationRepository organizations,
                                         CurrentUser currentUser) {
        this.balances = balances;
        this.organizations = organizations;
        this.currentUser = currentUser;
    }

    public record TopUpRequest(@NotNull @DecimalMin(value = "0.01") BigDecimal amountTjs,
                               @NotBlank String reference, String note) {}

    public record BalanceView(BigDecimal balanceTjs, Page<BillingLedgerEntry> ledger) {}

    private UUID resolveAndAssertReadable(String rma) {
        var org = organizations.findByRma(rma).orElseThrow(() -> new NotFoundException("Организация не найдена"));
        boolean own = currentUser.organizationRma().map(r -> r.equals(rma)).orElse(false);
        if (!own && !currentUser.hasRole("SYSTEM_ADMIN")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Недостаточно прав для просмотра баланса этой организации");
        }
        return org.getId();
    }

    @GetMapping
    public BalanceView get(@PathVariable String rma, @RequestParam(defaultValue = "0") int page,
                           @RequestParam(defaultValue = "50") int size) {
        var orgId = resolveAndAssertReadable(rma);
        return new BalanceView(balances.balance(orgId), balances.ledger(orgId, PageRequest.of(page, size)));
    }

    @PostMapping("/topup")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<BillingLedgerEntry> topUp(@PathVariable String rma, @Valid @RequestBody TopUpRequest req) {
        var org = organizations.findByRma(rma).orElseThrow(() -> new NotFoundException("Организация не найдена"));
        var actor = currentUser.subject().orElse("platform");
        var entry = balances.topUp(org.getId(), req.amountTjs(), req.reference(), req.note(), actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(entry);
    }

    /**
     * Списание за выдачу ПЛ — межсервисный вызов waybill-service (сервисный токен
     * API_INTEGRATOR, по аналогии с {@code PATCH /vehicles/{id}/odometer}). Не открыт конечному
     * пользователю отдельной ролью — любой аутентифицированный держатель API_INTEGRATOR токена
     * может списывать только по конкретному {@code waybillId}, идемпотентно (см. сервис).
     */
    @PostMapping("/debit")
    @PreAuthorize("hasRole('API_INTEGRATOR') or hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<Void> debit(@PathVariable String rma, @Valid @RequestBody DebitRequest req) {
        var org = organizations.findByRma(rma).orElseThrow(() -> new NotFoundException("Организация не найдена"));
        balances.debitForWaybill(org.getId(), req.waybillId(), req.waybillType(), req.amountTjs(), "waybill-service");
        return ResponseEntity.noContent().build();
    }

    public record DebitRequest(@NotNull UUID waybillId, @NotBlank String waybillType,
                               @NotNull @DecimalMin("0") BigDecimal amountTjs) {}
}
