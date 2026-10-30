package tj.mintrans.epd.masterdata.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tj.mintrans.epd.masterdata.config.CurrentUser;
import tj.mintrans.epd.masterdata.domain.WaybillFeeTariff;
import tj.mintrans.epd.masterdata.repository.OrganizationRepository;
import tj.mintrans.epd.masterdata.service.AuditService;
import tj.mintrans.epd.masterdata.service.WaybillFeeTariffService;
import tj.mintrans.epd.masterdata.web.error.NotFoundException;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Тариф за выдачу путевого листа по типу (решение владельца 31.10.2026). Только SYSTEM_ADMIN —
 * в отличие от движка политик (Policy), организация НЕ может сама себе переопределить ставку.
 */
@RestController
@RequestMapping("/api/v1/waybill-fee-tariffs")
public class WaybillFeeTariffController {

    private final WaybillFeeTariffService tariffs;
    private final OrganizationRepository organizations;
    private final CurrentUser currentUser;
    private final AuditService audit;

    public WaybillFeeTariffController(WaybillFeeTariffService tariffs, OrganizationRepository organizations,
                                      CurrentUser currentUser, AuditService audit) {
        this.tariffs = tariffs;
        this.organizations = organizations;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    public record NationalRequest(@NotBlank String waybillType, @NotNull @DecimalMin("0") BigDecimal amountTjs) {}

    public record OrganizationRequest(@NotBlank String waybillType, @NotNull UUID organizationId,
                                      @NotNull @DecimalMin("0") BigDecimal amountTjs) {}

    /**
     * Эффективная ставка по РМА организации и типу ПЛ — используется waybill-service (Т1).
     * Открыт любому аутентифицированному держателю токена (пользователю или сервисному) —
     * сама ставка не относится к персональным данным, в отличие от переопределений политик
     * другой организации ({@code PolicyController.effective}), поэтому тенант-ограничение здесь
     * не нужно: ставка одной и той же организации видна и ей самой, и платформе.
     */
    @GetMapping("/effective")
    public BigDecimal effective(@RequestParam String organizationRma, @RequestParam String waybillType) {
        var orgId = organizations.findByRma(organizationRma).map(o -> o.getId()).orElse(null);
        return tariffs.effectiveFee(waybillType, orgId);
    }

    @GetMapping
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public List<WaybillFeeTariff> list() {
        return tariffs.list();
    }

    @PostMapping("/national")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<WaybillFeeTariff> upsertNational(@Valid @RequestBody NationalRequest req) {
        var actor = currentUser.subject().orElse("platform");
        var saved = tariffs.upsertNational(req.waybillType(), req.amountTjs(), actor);
        audit.record(AuditService.UPDATE, "WAYBILL_FEE_TARIFF", "NATIONAL/" + req.waybillType(),
                null, req.amountTjs().toPlainString());
        return ResponseEntity.ok(saved);
    }

    @PostMapping("/organization")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<WaybillFeeTariff> upsertOrganization(@Valid @RequestBody OrganizationRequest req) {
        var actor = currentUser.subject().orElse("platform");
        var saved = tariffs.upsertOrganization(req.waybillType(), req.organizationId(), req.amountTjs(), actor);
        audit.record(AuditService.UPDATE, "WAYBILL_FEE_TARIFF",
                "ORG:" + req.organizationId() + "/" + req.waybillType(), null, req.amountTjs().toPlainString());
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        var existing = tariffs.list().stream().filter(t -> t.getId().equals(id)).findFirst()
                .orElseThrow(() -> new NotFoundException("Тариф не найден"));
        tariffs.delete(id);
        audit.record(AuditService.DELETE, "WAYBILL_FEE_TARIFF",
                (existing.getOrganizationId() == null ? "NATIONAL/" : "ORG:" + existing.getOrganizationId() + "/")
                        + existing.getWaybillType(), existing.getAmountTjs().toPlainString(), null);
        return ResponseEntity.noContent().build();
    }
}
