package tj.mintrans.epd.masterdata.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
import tj.mintrans.epd.masterdata.config.CurrentUser;
import tj.mintrans.epd.masterdata.domain.Driver;
import tj.mintrans.epd.masterdata.repository.DriverRepository;
import tj.mintrans.epd.masterdata.repository.OrganizationRepository;
import tj.mintrans.epd.masterdata.service.AuditService;
import tj.mintrans.epd.masterdata.web.error.NotFoundException;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Водители (ронандаҳо). Upsert по РМА.
 */
@RestController
@RequestMapping("/api/v1/drivers")
public class DriverController {

    private final DriverRepository drivers;
    private final OrganizationRepository organizations;
    private final CurrentUser currentUser;
    private final AuditService audit;

    public DriverController(DriverRepository drivers, OrganizationRepository organizations,
                            CurrentUser currentUser, AuditService audit) {
        this.drivers = drivers;
        this.organizations = organizations;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    public record DriverRequest(
            @NotBlank @Pattern(regexp = "\\d{9,10}", message = "РМА должен содержать 9–10 цифр") String rma,
            @NotBlank @Pattern(regexp = "\\d{9,10}", message = "РМА организации должен содержать 9–10 цифр") String organizationRma,
            String tabNumber,
            @NotBlank String fullName,
            String licenseNumber,
            String licenseCategories,
            LocalDate licenseValidTo,
            Short degree,
            String medCertNumber,
            LocalDate medCertValidTo,
            LocalDate safetyCourseValidTo,
            String phone) {
    }

    /**
     * Прямой upsert — только push-канал единой платформы (API_INTEGRATOR) и сисадмин.
     * Перевозчики добавляют водителей по ИНН через POST /api/v1/sync/driver
     * (ФИО — из налоговой, ВУ и медсправка — из ГАИ/Минздрава).
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('API_INTEGRATOR','SYSTEM_ADMIN')")
    public ResponseEntity<Driver> upsert(@Valid @RequestBody DriverRequest req) {
        var org = organizations.findByRma(req.organizationRma())
                .orElseThrow(() -> new NotFoundException("Организация не найдена"));
        var existing = drivers.findByRma(req.rma());
        var driver = existing.orElseGet(Driver::new);
        driver.setRma(req.rma());
        driver.setOrganizationId(org.getId());
        driver.setTabNumber(req.tabNumber());
        driver.setFullName(req.fullName());
        driver.setLicenseNumber(req.licenseNumber());
        driver.setLicenseCategories(req.licenseCategories());
        driver.setLicenseValidTo(req.licenseValidTo());
        driver.setDegree(req.degree());
        driver.setMedCertNumber(req.medCertNumber());
        driver.setMedCertValidTo(req.medCertValidTo());
        driver.setSafetyCourseValidTo(req.safetyCourseValidTo());
        driver.setPhone(req.phone());
        var saved = drivers.save(driver);
        audit.record(existing.isPresent() ? AuditService.UPDATE : AuditService.CREATE,
                "DRIVER", req.rma(), existing.map(Driver::getFullName).orElse(null), saved.getFullName());
        return ResponseEntity.status(existing.isPresent() ? HttpStatus.OK : HttpStatus.CREATED).body(saved);
    }

    @GetMapping
    public List<Driver> list(@RequestParam(required = false) String rma,
                             @RequestParam(required = false) String organizationRma) {
        // Мультиарендность: не-админ видит только водителей своей организации.
        // Анонимные (внутренние) вызовы не фильтруются.
        if (currentUser.isTenantScoped()) {
            var org = currentUser.organizationRma().flatMap(organizations::findByRma).orElse(null);
            if (org == null) {
                return List.of();
            }
            if (rma != null) {
                return drivers.findByRma(rma)
                        .filter(d -> org.getId().equals(d.getOrganizationId()))
                        .map(List::of).orElseGet(List::of);
            }
            return drivers.findByOrganizationId(org.getId());
        }
        if (rma != null) {
            return drivers.findByRma(rma).map(List::of).orElseGet(List::of);
        }
        if (organizationRma != null) {
            return organizations.findByRma(organizationRma)
                    .map(org -> drivers.findByOrganizationId(org.getId()))
                    .orElseGet(List::of);
        }
        return drivers.findAll();
    }

    @GetMapping("/{id}")
    public Driver get(@PathVariable UUID id) {
        var driver = drivers.findById(id).orElseThrow(() -> new NotFoundException("Водитель не найден"));
        // Мультиарендность: не-админ не может прочитать водителя чужой организации по прямому id.
        if (currentUser.isTenantScoped()) {
            var org = currentUser.organizationRma().flatMap(organizations::findByRma).orElse(null);
            if (org == null || !org.getId().equals(driver.getOrganizationId())) {
                throw new NotFoundException("Водитель не найден");
            }
        }
        return driver;
    }
}
