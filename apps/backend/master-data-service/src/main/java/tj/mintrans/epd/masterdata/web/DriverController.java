package tj.mintrans.epd.masterdata.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.data.domain.PageRequest;
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
import org.springframework.web.server.ResponseStatusException;
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
            LocalDate adrCertValidTo,
            String phone) {
    }

    /**
     * Нативное управление водителями внутри платформы: перевозчик (COMPANY_ADMIN/DISPATCHER)
     * ведёт водителей СВОЕЙ организации; платформенный push-канал единой платформы
     * (API_INTEGRATOR) и сисадмин — любую. Тенант не может писать в чужую организацию
     * (403) и «захватывать» водителя другой организации по РМА (409).
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('API_INTEGRATOR','SYSTEM_ADMIN','COMPANY_ADMIN','DISPATCHER')")
    public ResponseEntity<Driver> upsert(@Valid @RequestBody DriverRequest req) {
        requireOwnOrganization(req.organizationRma());
        var org = organizations.findByRma(req.organizationRma())
                .orElseThrow(() -> new NotFoundException("Организация не найдена"));
        var existing = drivers.findByRma(req.rma());
        assertNotForeign(existing.map(Driver::getOrganizationId).orElse(null), org.getId(), "Водитель");
        String oldName = existing.map(Driver::getFullName).orElse(null); // до мутации (existing и driver — один объект)
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
        driver.setAdrCertValidTo(req.adrCertValidTo());
        driver.setPhone(req.phone());
        var saved = drivers.save(driver);
        audit.record(existing.isPresent() ? AuditService.UPDATE : AuditService.CREATE,
                "DRIVER", req.rma(), oldName, saved.getFullName());
        return ResponseEntity.status(existing.isPresent() ? HttpStatus.OK : HttpStatus.CREATED).body(saved);
    }

    @GetMapping
    public List<Driver> list(@RequestParam(required = false) String rma,
                             @RequestParam(required = false) String organizationRma,
                             @RequestParam(required = false) String q,
                             @RequestParam(defaultValue = "25") int limit) {
        int cap = Math.min(Math.max(limit, 1), 100);
        // Мультиарендность: не-админ видит только водителей своей организации.
        // Анонимные (внутренние) вызовы не фильтруются.
        if (currentUser.isTenantScoped()) {
            var org = currentUser.organizationRma().flatMap(organizations::findByRma).orElse(null);
            if (org == null) {
                return List.of();
            }
            // q — подстрочный поиск по ИНН(РМА) или ФИО с лимитом (тысячи водителей).
            if (q != null) {
                return drivers.searchByOrg(org.getId(), q.trim(), PageRequest.of(0, cap));
            }
            if (rma != null) {
                return drivers.findByRma(rma)
                        .filter(d -> org.getId().equals(d.getOrganizationId()))
                        .map(List::of).orElseGet(List::of);
            }
            return drivers.findByOrganizationId(org.getId());
        }
        if (q != null && organizationRma != null) {
            var org = organizations.findByRma(organizationRma).orElse(null);
            return org == null ? List.of() : drivers.searchByOrg(org.getId(), q.trim(), PageRequest.of(0, cap));
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

    /** Удаление водителя своей организации. Историю ПЛ не рушит — путевые листы хранят
     *  снимок данных водителя на момент выдачи (master-data и waybill — раздельные БД). */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','COMPANY_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        var driver = drivers.findById(id).orElseThrow(() -> new NotFoundException("Водитель не найден"));
        requireOwnEntity(driver.getOrganizationId());
        drivers.delete(driver);
        audit.record(AuditService.DELETE, "DRIVER", driver.getRma(), driver.getFullName(), null);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------ тенант-защита записи

    /** Тенант пишет только в свою организацию (иначе 403); платформенный админ — в любую. */
    private void requireOwnOrganization(String organizationRma) {
        if (currentUser.isTenantScoped()) {
            var own = currentUser.organizationRma();
            if (own.isEmpty() || !own.get().equals(organizationRma)) {
                throw new AccessDeniedException("Доступ только к своей организации");
            }
        }
    }

    /** Нельзя «захватить»/изменить сущность, уже закреплённую за другой организацией (409). */
    private void assertNotForeign(UUID existingOrgId, UUID targetOrgId, String what) {
        if (currentUser.isTenantScoped() && existingOrgId != null && !existingOrgId.equals(targetOrgId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, what + " уже закреплён(а) за другой организацией");
        }
    }

    /** Тенант удаляет/меняет только сущность своей организации (иначе 403). */
    private void requireOwnEntity(UUID entityOrgId) {
        if (currentUser.isTenantScoped()) {
            var own = currentUser.organizationRma().flatMap(organizations::findByRma).orElse(null);
            if (own == null || !own.getId().equals(entityOrgId)) {
                throw new AccessDeniedException("Доступ только к своей организации");
            }
        }
    }
}
