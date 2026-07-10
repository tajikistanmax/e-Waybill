package tj.mintrans.epd.masterdata.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
import tj.mintrans.epd.masterdata.domain.Employee;
import tj.mintrans.epd.masterdata.repository.EmployeeRepository;
import tj.mintrans.epd.masterdata.repository.OrganizationRepository;
import tj.mintrans.epd.masterdata.web.error.NotFoundException;

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

    public EmployeeController(EmployeeRepository employees, OrganizationRepository organizations) {
        this.employees = employees;
        this.organizations = organizations;
    }

    public record EmployeeRequest(
            @NotBlank @Pattern(regexp = "\\d{9,10}", message = "РМА должен содержать 9–10 цифр") String rma,
            @NotBlank @Pattern(regexp = "\\d{9,10}", message = "РМА организации должен содержать 9–10 цифр") String organizationRma,
            String tabNumber,
            @NotBlank String name,
            @NotNull @Min(1) @Max(3) Short type,
            String phone) {
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('COMPANY_ADMIN','SYSTEM_ADMIN')")
    public ResponseEntity<Employee> upsert(@Valid @RequestBody EmployeeRequest req) {
        var org = organizations.findByRma(req.organizationRma())
                .orElseThrow(() -> new NotFoundException("Организация не найдена"));
        var existing = employees.findByRma(req.rma());
        var employee = existing.orElseGet(Employee::new);
        employee.setRma(req.rma());
        employee.setOrganizationId(org.getId());
        employee.setTabNumber(req.tabNumber());
        employee.setName(req.name());
        employee.setType(req.type());
        employee.setPhone(req.phone());
        var saved = employees.save(employee);
        return ResponseEntity.status(existing.isPresent() ? HttpStatus.OK : HttpStatus.CREATED).body(saved);
    }

    @GetMapping
    public List<Employee> list(@RequestParam(required = false) String rma,
                               @RequestParam(required = false) String organizationRma) {
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
        return employees.findById(id).orElseThrow(() -> new NotFoundException("Сотрудник не найден"));
    }
}
