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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tj.mintrans.epd.masterdata.client.UnifiedPlatformClient;
import tj.mintrans.epd.masterdata.config.CurrentUser;
import tj.mintrans.epd.masterdata.domain.Driver;
import tj.mintrans.epd.masterdata.domain.Employee;
import tj.mintrans.epd.masterdata.domain.Organization;
import tj.mintrans.epd.masterdata.domain.Vehicle;
import tj.mintrans.epd.masterdata.repository.DriverRepository;
import tj.mintrans.epd.masterdata.repository.EmployeeRepository;
import tj.mintrans.epd.masterdata.repository.OrganizationRepository;
import tj.mintrans.epd.masterdata.repository.VehicleRepository;
import tj.mintrans.epd.masterdata.service.AuditService;
import tj.mintrans.epd.masterdata.web.error.NotFoundException;

import java.time.OffsetDateTime;

/**
 * Синхронизация с единой платформой транспорта Минтранса — ЕДИНСТВЕННЫЙ путь
 * появления субъектов и объектов в ЭПД для перевозчиков.
 *
 * Субъекты (физлицо/ИП/юрлицо) и объекты (ТС) регистрируются один раз в единой
 * платформе: данные субъекта приходят из налоговой по ИНН, ТС — из базы ГАИ по
 * госномеру, ВУ и медсправка — из ГАИ/Минздрава. Здесь пользователь указывает
 * только идентификатор (ИНН/госномер) — все данные подтягиваются и сохраняются
 * как реплика (source=UNIFIED, synced_at).
 *
 * Прямые upsert-эндпоинты (/organizations, /drivers, /vehicles, /employees)
 * оставлены только для push-канала единой платформы (роль API_INTEGRATOR) и
 * системного администратора.
 */
@RestController
@RequestMapping("/api/v1/sync")
public class SyncController {

    private final UnifiedPlatformClient unifiedPlatform;
    private final OrganizationRepository organizations;
    private final DriverRepository drivers;
    private final VehicleRepository vehicles;
    private final EmployeeRepository employees;
    private final CurrentUser currentUser;
    private final AuditService audit;

    public SyncController(UnifiedPlatformClient unifiedPlatform,
                          OrganizationRepository organizations,
                          DriverRepository drivers,
                          VehicleRepository vehicles,
                          EmployeeRepository employees,
                          CurrentUser currentUser,
                          AuditService audit) {
        this.unifiedPlatform = unifiedPlatform;
        this.organizations = organizations;
        this.drivers = drivers;
        this.vehicles = vehicles;
        this.employees = employees;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    // ------------------------------------------------------------ запросы

    public record SyncOrganizationRequest(
            @NotBlank @Pattern(regexp = "\\d{9,10}", message = "ИНН должен содержать 9–10 цифр") String inn) {
    }

    public record SyncDriverRequest(
            @NotBlank @Pattern(regexp = "\\d{9,10}", message = "ИНН должен содержать 9–10 цифр") String inn,
            @NotBlank @Pattern(regexp = "\\d{9,10}") String organizationRma,
            String tabNumber) {
    }

    public record SyncVehicleRequest(
            @NotBlank String registrationNumber,
            @NotBlank @Pattern(regexp = "\\d{9,10}") String organizationRma,
            @Pattern(regexp = "\\d{4}") String parkingNumber) {
    }

    public record SyncEmployeeRequest(
            @NotBlank @Pattern(regexp = "\\d{9,10}", message = "ИНН должен содержать 9–10 цифр") String inn,
            @NotBlank @Pattern(regexp = "\\d{9,10}") String organizationRma,
            @NotNull @Min(1) @Max(3) Short type,
            String tabNumber) {
    }

    // ------------------------------------------------------------ субъекты

    /** Организация/ИП/физлицо-перевозчик по ИНН — данные из налоговой + лицензия. */
    @PostMapping("/organization")
    @PreAuthorize("hasAnyRole('COMPANY_ADMIN','SYSTEM_ADMIN')")
    public ResponseEntity<Organization> syncOrganization(@Valid @RequestBody SyncOrganizationRequest req) {
        requireOwnOrganization(req.inn());
        var subject = unifiedPlatform.findSubject(req.inn())
                .orElseThrow(() -> new NotFoundException("Субъект с ИНН %s не найден в единой платформе (налоговая)".formatted(req.inn())));
        var existing = organizations.findByRma(subject.inn());
        String oldName = existing.map(Organization::getName).orElse(null); // до мутации (existing и org — один объект)
        var org = existing.orElseGet(Organization::new);
        org.setRma(subject.inn());
        org.setName(subject.name());
        org.setSubjectType(subject.subjectType());
        org.setRegionId(subject.regionId());
        org.setCityName(subject.cityName());
        org.setAddress(subject.address());
        org.setPhone(subject.phone());
        org.setEmail(subject.email());
        org.setNameHead(subject.headName());
        org.setLicenseFrom(subject.licenseFrom());
        org.setLicenseTo(subject.licenseTo());
        org.setSource("UNIFIED");
        org.setSyncedAt(OffsetDateTime.now());
        var savedOrg = organizations.save(org);
        audit.record(existing.isPresent() ? AuditService.UPDATE : AuditService.CREATE,
                "ORGANIZATION", subject.inn(), oldName, savedOrg.getName());
        return saved(existing.isPresent(), savedOrg);
    }

    /** Водитель по ИНН — ФИО из налоговой, ВУ и медсправка из ГАИ/Минздрава. */
    @PostMapping("/driver")
    @PreAuthorize("hasAnyRole('COMPANY_ADMIN','SYSTEM_ADMIN')")
    public ResponseEntity<Driver> syncDriver(@Valid @RequestBody SyncDriverRequest req) {
        requireOwnOrganization(req.organizationRma());
        var org = requireOrganization(req.organizationRma());
        var subject = unifiedPlatform.findSubject(req.inn())
                .orElseThrow(() -> new NotFoundException("Субъект с ИНН %s не найден в единой платформе (налоговая)".formatted(req.inn())));
        var license = unifiedPlatform.findDriverLicense(req.inn())
                .orElseThrow(() -> new NotFoundException("Водительское удостоверение для ИНН %s не найдено в базе ГАИ".formatted(req.inn())));
        var existing = drivers.findByRma(subject.inn());
        existing.ifPresent(d -> assertNotForeign(d.getOrganizationId(), org.getId(), "Водитель"));
        String oldName = existing.map(Driver::getFullName).orElse(null); // до мутации
        var driver = existing.orElseGet(Driver::new);
        driver.setRma(subject.inn());
        driver.setOrganizationId(org.getId());
        driver.setFullName(subject.name());
        driver.setPhone(subject.phone());
        driver.setLicenseNumber(license.licenseNumber());
        driver.setLicenseCategories(license.categories());
        driver.setLicenseValidTo(license.validTo());
        driver.setMedCertNumber(license.medCertNumber());
        driver.setMedCertValidTo(license.medCertValidTo());
        if (req.tabNumber() != null && !req.tabNumber().isBlank()) driver.setTabNumber(req.tabNumber());
        driver.setSource("UNIFIED");
        driver.setSyncedAt(OffsetDateTime.now());
        var savedDriver = drivers.save(driver);
        audit.record(existing.isPresent() ? AuditService.UPDATE : AuditService.CREATE,
                "DRIVER", subject.inn(), oldName, savedDriver.getFullName());
        return saved(existing.isPresent(), savedDriver);
    }

    /** Сотрудник (врач/механик/диспетчер) по ИНН — ФИО из налоговой, роль локальная. */
    @PostMapping("/employee")
    @PreAuthorize("hasAnyRole('COMPANY_ADMIN','SYSTEM_ADMIN')")
    public ResponseEntity<Employee> syncEmployee(@Valid @RequestBody SyncEmployeeRequest req) {
        requireOwnOrganization(req.organizationRma());
        var org = requireOrganization(req.organizationRma());
        var subject = unifiedPlatform.findSubject(req.inn())
                .orElseThrow(() -> new NotFoundException("Субъект с ИНН %s не найден в единой платформе (налоговая)".formatted(req.inn())));
        var existing = employees.findByRma(subject.inn());
        existing.ifPresent(e -> assertNotForeign(e.getOrganizationId(), org.getId(), "Сотрудник"));
        String oldName = existing.map(Employee::getName).orElse(null); // до мутации
        var employee = existing.orElseGet(Employee::new);
        employee.setRma(subject.inn());
        employee.setOrganizationId(org.getId());
        employee.setName(subject.name());
        employee.setPhone(subject.phone());
        employee.setType(req.type());
        if (req.tabNumber() != null && !req.tabNumber().isBlank()) employee.setTabNumber(req.tabNumber());
        employee.setSource("UNIFIED");
        employee.setSyncedAt(OffsetDateTime.now());
        var savedEmployee = employees.save(employee);
        audit.record(existing.isPresent() ? AuditService.UPDATE : AuditService.CREATE,
                "EMPLOYEE", subject.inn(), oldName, savedEmployee.getName());
        return saved(existing.isPresent(), savedEmployee);
    }

    // ------------------------------------------------------------ объекты

    /** ТС по госномеру — марка, VIN, год, вместимость, техосмотр из базы ГАИ. */
    @PostMapping("/vehicle")
    @PreAuthorize("hasAnyRole('COMPANY_ADMIN','SYSTEM_ADMIN')")
    public ResponseEntity<Vehicle> syncVehicle(@Valid @RequestBody SyncVehicleRequest req) {
        requireOwnOrganization(req.organizationRma());
        var org = requireOrganization(req.organizationRma());
        var info = unifiedPlatform.findVehicle(req.registrationNumber())
                .orElseThrow(() -> new NotFoundException("ТС %s не найдено в базе ГАИ".formatted(req.registrationNumber())));
        // Каноническая форма госномера (как в прямом upsert) — исключаем раздвоение ТС по регистру.
        var canonicalNumber = info.registrationNumber() == null
                ? null : info.registrationNumber().trim().toUpperCase();
        var existing = vehicles.findByRegistrationNumber(canonicalNumber);
        existing.ifPresent(v -> assertNotForeign(v.getOrganizationId(), org.getId(), "Транспорт"));
        String oldBrand = existing.map(Vehicle::getBrand).orElse(null); // до мутации
        var vehicle = existing.orElseGet(Vehicle::new);
        vehicle.setRegistrationNumber(canonicalNumber);
        vehicle.setOrganizationId(org.getId());
        vehicle.setTransportType(info.transportType());
        vehicle.setBrand(info.brand());
        vehicle.setVincode(info.vincode());
        vehicle.setYearManufacture(info.yearManufacture());
        vehicle.setCapacity(info.capacity());
        vehicle.setCarrying(info.carrying());
        vehicle.setTechInspectionValidTo(info.techInspectionValidTo());
        vehicle.setControlCardValidTo(info.controlCardValidTo());
        if (req.parkingNumber() != null && !req.parkingNumber().isBlank()) vehicle.setParkingNumber(req.parkingNumber());
        vehicle.setSource("UNIFIED");
        vehicle.setSyncedAt(OffsetDateTime.now());
        var savedVehicle = vehicles.save(vehicle);
        audit.record(existing.isPresent() ? AuditService.UPDATE : AuditService.CREATE,
                "VEHICLE", canonicalNumber, oldBrand, savedVehicle.getBrand());
        return saved(existing.isPresent(), savedVehicle);
    }

    // ------------------------------------------------------------ дозволы (E-PERMIT)

    /** Онлайн-проверка дозвола на международную перевозку (система E-PERMIT). */
    @org.springframework.web.bind.annotation.GetMapping("/permit/{number}")
    public UnifiedPlatformClient.PermitInfo permit(@org.springframework.web.bind.annotation.PathVariable String number) {
        return unifiedPlatform.findPermit(number)
                .orElseThrow(() -> new NotFoundException("Дозвол %s не найден в системе E-PERMIT".formatted(number)));
    }

    // ------------------------------------------------------------ вспомогательное

    /**
     * Защита от межтенантного «захвата»: если сущность с этим ИНН/госномером уже закреплена
     * за другой организацией — 409 (перевод между организациями должен быть отдельной операцией).
     * Для platform-admin (не tenant-scoped) разрешено.
     */
    private void assertNotForeign(java.util.UUID existingOrgId, java.util.UUID targetOrgId, String what) {
        if (currentUser.isTenantScoped() && existingOrgId != null && !existingOrgId.equals(targetOrgId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    what + " уже закреплён(а) за другой организацией");
        }
    }

    /** Мультиарендность: администратор компании синхронизирует только свою организацию. */
    private void requireOwnOrganization(String organizationRma) {
        if (currentUser.isTenantScoped()) {
            var own = currentUser.organizationRma();
            if (own.isEmpty() || !own.get().equals(organizationRma)) {
                throw new org.springframework.security.access.AccessDeniedException(
                        "Доступ только к своей организации");
            }
        }
    }

    private Organization requireOrganization(String rma) {
        return organizations.findByRma(rma)
                .orElseThrow(() -> new NotFoundException(
                        "Организация %s не найдена — сначала выполните /sync/organization".formatted(rma)));
    }

    private static <T> ResponseEntity<T> saved(boolean existed, T body) {
        return ResponseEntity.status(existed ? HttpStatus.OK : HttpStatus.CREATED).body(body);
    }
}
