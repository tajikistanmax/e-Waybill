package tj.mintrans.epd.masterdata.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
import org.springframework.web.server.ResponseStatusException;
import tj.mintrans.epd.masterdata.config.TenantScope;
import tj.mintrans.epd.masterdata.domain.Employee;
import tj.mintrans.epd.masterdata.repository.EmployeeRepository;
import tj.mintrans.epd.masterdata.repository.OrganizationRepository;
import tj.mintrans.epd.masterdata.service.AuditService;
import tj.mintrans.epd.masterdata.web.error.NotFoundException;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Сотрудники (врач/механик/диспетчер). Upsert по РМА.
 */
@RestController
@RequestMapping("/api/v1/employees")
public class EmployeeController {

    private final EmployeeRepository employees;
    private final OrganizationRepository organizations;
    private final TenantScope tenantScope;
    private final AuditService audit;

    public EmployeeController(EmployeeRepository employees, OrganizationRepository organizations,
                              TenantScope tenantScope, AuditService audit) {
        this.employees = employees;
        this.organizations = organizations;
        this.tenantScope = tenantScope;
        this.audit = audit;
    }

    public record EmployeeRequest(
            @NotBlank @Pattern(regexp = "\\d{9,10}", message = "РМА должен содержать 9–10 цифр") String rma,
            @NotBlank @Pattern(regexp = "\\d{9,10}", message = "РМА организации должен содержать 9–10 цифр") String organizationRma,
            String tabNumber,
            @NotBlank String name,
            @NotNull @Min(1) @Max(3) Short type,
            String phone,
            String address,
            // Сертификат врача (§8 QA). Актуален для type=1; у механика/диспетчера обычно пуст.
            // certValidTo — ISO-дата (yyyy-MM-dd); некорректный формат отклоняется на десериализации.
            String certNumber,
            LocalDate certValidTo) {
    }

    /**
     * Нативное управление сотрудниками (врач/механик/диспетчер) внутри платформы: администратор
     * компании ведёт сотрудников СВОЕЙ организации; push-канал единой платформы (API_INTEGRATOR)
     * и сисадмин — любых. Тенант не пишет в чужую организацию (403), не «захватывает» по РМА (409).
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('API_INTEGRATOR','SYSTEM_ADMIN','COMPANY_ADMIN','BRANCH_ADMIN','DISPATCHER')")
    public ResponseEntity<Employee> upsert(@Valid @RequestBody EmployeeRequest req) {
        requireWritable(req.organizationRma());
        var org = organizations.findByRma(req.organizationRma())
                .orElseThrow(() -> new NotFoundException("Организация не найдена"));
        var existing = employees.findByRma(req.rma());
        assertNotForeign(existing.map(Employee::getOrganizationId).orElse(null), org.getId(), "Сотрудник");
        String oldName = existing.map(Employee::getName).orElse(null); // до мутации (existing и employee — один объект)
        var employee = existing.orElseGet(Employee::new);
        employee.setRma(req.rma());
        employee.setOrganizationId(org.getId());
        employee.setTabNumber(req.tabNumber());
        employee.setName(req.name());
        employee.setType(req.type());
        employee.setPhone(req.phone());
        employee.setAddress(req.address());
        employee.setCertNumber(req.certNumber());
        employee.setCertValidTo(req.certValidTo());
        var saved = employees.save(employee);
        audit.record(existing.isPresent() ? AuditService.UPDATE : AuditService.CREATE,
                "EMPLOYEE", req.rma(), oldName, saved.getName());
        return ResponseEntity.status(existing.isPresent() ? HttpStatus.OK : HttpStatus.CREATED).body(saved);
    }

    @GetMapping
    public List<Employee> list(@RequestParam(required = false) String rma,
                               @RequestParam(required = false) String organizationRma) {
        // Мультиарендность: тенант видит сотрудников своей организации и (для
        // администратора компании) всех её филиалов. Анонимные (внутренние) вызовы не фильтруются.
        if (tenantScope.isBounded()) {
            var ids = tenantScope.organizationIds();
            if (ids.isEmpty()) {
                return List.of();
            }
            if (rma != null) {
                return employees.findByRma(rma)
                        .filter(e -> ids.contains(e.getOrganizationId()))
                        .map(List::of).orElseGet(List::of);
            }
            return employees.findByOrganizationIdIn(ids);
        }
        if (rma != null) {
            return employees.findByRma(rma).map(List::of).orElseGet(List::of);
        }
        if (organizationRma != null) {
            return organizations.findByRma(organizationRma)
                    .map(org -> employees.findByOrganizationId(org.getId()))
                    .orElseGet(List::of);
        }
        return employees.findAll();
    }

    @GetMapping("/{id}")
    public Employee get(@PathVariable UUID id) {
        var employee = employees.findById(id).orElseThrow(() -> new NotFoundException("Сотрудник не найден"));
        // Мультиарендность: тенант не может прочитать сотрудника вне своей области по прямому id.
        if (tenantScope.isBounded() && !tenantScope.organizationIds().contains(employee.getOrganizationId())) {
            throw new NotFoundException("Сотрудник не найден");
        }
        return employee;
    }

    /** Открепление (удаление) сотрудника от организации (диспетчер/админ компании/филиала/сисадмин). */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','COMPANY_ADMIN','BRANCH_ADMIN','DISPATCHER')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        var employee = employees.findById(id).orElseThrow(() -> new NotFoundException("Сотрудник не найден"));
        requireOwnEntity(employee.getOrganizationId());
        employees.delete(employee);
        audit.record(AuditService.DELETE, "EMPLOYEE", employee.getRma(), employee.getName(), null);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------ тенант-защита записи

    /** Тенант пишет в свою организацию либо (администратор компании) в её филиал; иначе 403. */
    private void requireWritable(String organizationRma) {
        if (tenantScope.isBounded() && !tenantScope.canWrite(organizationRma)) {
            throw new AccessDeniedException("Доступ только к своим организациям");
        }
    }

    private void assertNotForeign(UUID existingOrgId, UUID targetOrgId, String what) {
        if (tenantScope.isBounded() && existingOrgId != null && !existingOrgId.equals(targetOrgId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, what + " уже закреплён(а) за другой организацией");
        }
    }

    private void requireOwnEntity(UUID entityOrgId) {
        if (tenantScope.isBounded() && !tenantScope.organizationIds().contains(entityOrgId)) {
            throw new AccessDeniedException("Доступ только к своим организациям");
        }
    }
}
