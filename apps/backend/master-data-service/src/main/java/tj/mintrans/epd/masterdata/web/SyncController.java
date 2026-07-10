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

    public SyncController(UnifiedPlatformClient unifiedPlatform,
                          OrganizationRepository organizations,
                          DriverRepository drivers,
                          VehicleRepository vehicles,
                          EmployeeRepository employees,
                          CurrentUser currentUser) {
        this.unifiedPlatform = unifiedPlatform;
        this.organizations = organizations;
        this.drivers = drivers;
        this.vehicles = vehicles;
        this.employees = employees;
        this.currentUser = currentUser;
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
        return saved(existing.isPresent(), organizations.save(org));
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
        return saved(existing.isPresent(), drivers.save(driver));
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
        var employee = existing.orElseGet(Employee::new);
        employee.setRma(subject.inn());
        employee.setOrganizationId(org.getId());
        employee.setName(subject.name());
        employee.setPhone(subject.phone());
        employee.setType(req.type());
        if (req.tabNumber() != null && !req.tabNumber().isBlank()) employee.setTabNumber(req.tabNumber());
        employee.setSource("UNIFIED");
        employee.setSyncedAt(OffsetDateTime.now());
        return saved(existing.isPresent(), employees.save(employee));
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
        var existing = vehicles.findByRegistrationNumber(info.registrationNumber());
        var vehicle = existing.orElseGet(Vehicle::new);
        vehicle.setRegistrationNumber(info.registrationNumber());
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
        return saved(existing.isPresent(), vehicles.save(vehicle));
    }

    // ------------------------------------------------------------ вспомогательное

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
