package tj.mintrans.epd.masterdata.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
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
import tj.mintrans.epd.masterdata.domain.Organization;
import tj.mintrans.epd.masterdata.repository.OrganizationRepository;
import tj.mintrans.epd.masterdata.service.AuditService;
import tj.mintrans.epd.masterdata.web.error.NotFoundException;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Организации-перевозчики. Upsert по РМА (legacy-семантика rohkhat.tj).
 */
@RestController
@RequestMapping("/api/v1/organizations")
public class OrganizationController {

    private final OrganizationRepository repository;
    private final CurrentUser currentUser;
    private final AuditService audit;

    public OrganizationController(OrganizationRepository repository, CurrentUser currentUser, AuditService audit) {
        this.repository = repository;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    public record OrganizationRequest(
            @NotBlank @Pattern(regexp = "\\d{9,10}", message = "РМА должен содержать 9–10 цифр") String rma,
            String kpp,
            @NotBlank String name,
            Short typeCompany,
            @Min(value = 1, message = "Код региона: 1–7") @Max(value = 7, message = "Код региона: 1–7") Short regionId,
            String cityName,
            String address,
            String phone,
            String email,
            String nameHead,
            String bank,
            LocalDate licenseFrom,
            LocalDate licenseTo) {
    }

    /**
     * Нативное управление: администратор компании редактирует реквизиты СВОЕЙ организации
     * (rma совпадает); создание/редактирование любой организации — push-канал единой платформы
     * (API_INTEGRATOR) и системный администратор. Тенант в чужую организацию писать не может (403).
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('API_INTEGRATOR','SYSTEM_ADMIN','COMPANY_ADMIN')")
    public ResponseEntity<Organization> upsert(@Valid @RequestBody OrganizationRequest req) {
        requireOwnOrganization(req.rma());
        var existing = repository.findByRma(req.rma());
        String oldName = existing.map(Organization::getName).orElse(null); // до мутации (existing и org — один объект)
        var org = existing.orElseGet(Organization::new);
        org.setRma(req.rma());
        org.setKpp(req.kpp());
        org.setName(req.name());
        if (req.typeCompany() != null) org.setTypeCompany(req.typeCompany());
        org.setRegionId(req.regionId());
        org.setCityName(req.cityName());
        org.setAddress(req.address());
        org.setPhone(req.phone());
        org.setEmail(req.email());
        org.setNameHead(req.nameHead());
        org.setBank(req.bank());
        org.setLicenseFrom(req.licenseFrom());
        org.setLicenseTo(req.licenseTo());
        var saved = repository.save(org);
        audit.record(existing.isPresent() ? AuditService.UPDATE : AuditService.CREATE,
                "ORGANIZATION", req.rma(), oldName, saved.getName());
        return ResponseEntity.status(existing.isPresent() ? HttpStatus.OK : HttpStatus.CREATED).body(saved);
    }

    @GetMapping
    public List<Organization> list(@RequestParam(required = false) String rma) {
        // Мультиарендность: не-админ видит только свою организацию (claim organization_rma).
        // Анонимные (внутренние) вызовы не фильтруются.
        if (currentUser.isTenantScoped()) {
            return currentUser.organizationRma()
                    .flatMap(repository::findByRma)
                    .map(List::of).orElseGet(List::of);
        }
        if (rma != null) {
            return repository.findByRma(rma).map(List::of).orElseGet(List::of);
        }
        return repository.findAll();
    }

    @GetMapping("/{id}")
    public Organization get(@PathVariable UUID id) {
        var org = repository.findById(id).orElseThrow(() -> new NotFoundException("Организация не найдена"));
        // Мультиарендность: не-админ может прочитать только свою организацию.
        if (currentUser.isTenantScoped()
                && !currentUser.organizationRma().map(rma -> rma.equals(org.getRma())).orElse(false)) {
            throw new NotFoundException("Организация не найдена");
        }
        return org;
    }

    /** Удаление организации — только системный администратор Минтранса (не тенант). */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        var org = repository.findById(id).orElseThrow(() -> new NotFoundException("Организация не найдена"));
        repository.delete(org);
        audit.record(AuditService.DELETE, "ORGANIZATION", org.getRma(), org.getName(), null);
        return ResponseEntity.noContent().build();
    }

    /** Тенант (COMPANY_ADMIN) пишет только в свою организацию (иначе 403); админ — в любую. */
    private void requireOwnOrganization(String organizationRma) {
        if (currentUser.isTenantScoped()) {
            var own = currentUser.organizationRma();
            if (own.isEmpty() || !own.get().equals(organizationRma)) {
                throw new AccessDeniedException("Доступ только к своей организации");
            }
        }
    }
}
