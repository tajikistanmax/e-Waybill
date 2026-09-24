package tj.mintrans.epd.masterdata.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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
import tj.mintrans.epd.masterdata.config.TenantScope;
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
    private final tj.mintrans.epd.masterdata.repository.VehicleRepository vehicles;
    private final CurrentUser currentUser;
    private final TenantScope tenantScope;
    private final AuditService audit;
    private final tj.mintrans.epd.masterdata.service.DriverTabNumbers tabNumbers;
    private final tj.mintrans.epd.masterdata.service.RegistryQuery registryQuery;
    private final tj.mintrans.epd.masterdata.service.FormFieldPolicy formFields;
    private final tj.mintrans.epd.masterdata.service.MasterDataSourcePolicy sourcePolicy;

    public DriverController(DriverRepository drivers, OrganizationRepository organizations,
                            tj.mintrans.epd.masterdata.repository.VehicleRepository vehicles,
                            CurrentUser currentUser, TenantScope tenantScope, AuditService audit,
                            tj.mintrans.epd.masterdata.service.DriverTabNumbers tabNumbers,
                            tj.mintrans.epd.masterdata.service.RegistryQuery registryQuery,
                            tj.mintrans.epd.masterdata.service.FormFieldPolicy formFields,
                            tj.mintrans.epd.masterdata.service.MasterDataSourcePolicy sourcePolicy) {
        this.registryQuery = registryQuery;
        this.formFields = formFields;
        this.sourcePolicy = sourcePolicy;
        this.drivers = drivers;
        this.organizations = organizations;
        this.vehicles = vehicles;
        this.currentUser = currentUser;
        this.tenantScope = tenantScope;
        this.audit = audit;
        this.tabNumbers = tabNumbers;
    }

    public record DriverRequest(
            @NotBlank @Pattern(regexp = "\\d{9,10}", message = "РМА должен содержать 9–10 цифр") String rma,
            @NotBlank @Pattern(regexp = "\\d{9,10}", message = "РМА организации должен содержать 9–10 цифр") String organizationRma,
            String tabNumber,
            @NotBlank String fullName,
            @Past(message = "Дата рождения должна быть в прошлом") LocalDate birthDate,
            @Min(value = 0, message = "Стаж: 0–80 лет") @Max(value = 80, message = "Стаж: 0–80 лет") Short experienceYears,
            @Size(max = 500, message = "Медограничения: не более 500 символов") String medRestrictions,
            String licenseNumber,
            String licenseCategories,
            LocalDate licenseValidTo,
            // Класс водителя (дараҷа) 1–3 — legacy degree 1..3 (MIGRATION.md 12.7); питает надбавку cat_1/2/3 в зарплате.
            @Min(value = 1, message = "Класс водителя: 1–3") @Max(value = 3, message = "Класс водителя: 1–3") Short degree,
            String medCertNumber,
            LocalDate medCertValidTo,
            LocalDate safetyCourseValidTo,
            String safetyCourseNumber,
            LocalDate adrCertValidTo,
            String phone,
            // Реквизиты для паритета с боевой формой driver/create (MinTransRT):
            String passport,
            String address,
            @jakarta.validation.constraints.Email(message = "Некорректный email") String email,
            String powerAttorney,
            LocalDate visaValidTo,
            String contractNumber,
            LocalDate contractValidTo,
            UUID assignedVehicleId,
            Boolean suspended) {
    }

    /**
     * Нативное управление водителями внутри платформы: перевозчик
     * (COMPANY_ADMIN — по всем своим филиалам, BRANCH_ADMIN/DISPATCHER — по своему)
     * ведёт водителей своей области; платформенный push-канал единой платформы
     * (API_INTEGRATOR) и сисадмин — любую. Тенант не может писать вне своей области
     * (403) и «захватывать» водителя другой организации по РМА (409).
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('API_INTEGRATOR','SYSTEM_ADMIN','COMPANY_ADMIN','BRANCH_ADMIN','DISPATCHER')")
    public ResponseEntity<Driver> upsert(@Valid @RequestBody DriverRequest req) {
        requireWritable(req.organizationRma());
        var org = organizations.findByRma(req.organizationRma())
                .orElseThrow(() -> new NotFoundException("Организация не найдена"));
        var existing = drivers.findByRma(req.rma());
        assertNotForeign(existing.map(Driver::getOrganizationId).orElse(null), org.getId(), "Водитель");
        boolean integrator = currentUser.hasRole("API_INTEGRATOR");
        if (!integrator) {
            // Кто ведёт справочник (Настройки → Интеграции): при UNIFIED новую запись вручную не
            // завести, у записи из единой платформы меняются только поля модуля.
            var ex = existing.orElse(null);
            String src = ex == null ? null : ex.getSource();
            req = sourcePolicy.guardManualWrite(tj.mintrans.epd.masterdata.service.FormFieldPolicy.DRIVER, req, ex, src);
            // Обязательные по настройке поля (Настройки → Поля водителя) — для ручного ввода;
            // push-канал единой платформы (API_INTEGRATOR) не проверяется.
            formFields.requireFilled(tj.mintrans.epd.masterdata.service.FormFieldPolicy.DRIVER, req,
                    sourcePolicy.skipRequired(tj.mintrans.epd.masterdata.service.FormFieldPolicy.DRIVER, ex, src));
        }
        String oldName = existing.map(Driver::getFullName).orElse(null); // до мутации (existing и driver — один объект)
        var driver = existing.orElseGet(Driver::new);
        driver.setRma(req.rma());
        driver.setOrganizationId(org.getId());
        // Табельный номер: задан → как есть; иначе прежний; иначе max+1 по организации (legacy DriverObserver, 11.7).
        driver.setTabNumber(tabNumbers.resolve(req.tabNumber(), existing, org.getId()));
        driver.setFullName(req.fullName());
        driver.setBirthDate(req.birthDate());
        driver.setExperienceYears(req.experienceYears());
        if (req.medRestrictions() != null) {
            driver.setMedRestrictions(req.medRestrictions().isBlank() ? null : req.medRestrictions().trim());
        }
        driver.setLicenseNumber(req.licenseNumber());
        driver.setLicenseCategories(req.licenseCategories());
        driver.setLicenseValidTo(req.licenseValidTo());
        driver.setDegree(req.degree());
        driver.setMedCertNumber(req.medCertNumber());
        driver.setMedCertValidTo(req.medCertValidTo());
        driver.setSafetyCourseValidTo(req.safetyCourseValidTo());
        if (req.safetyCourseNumber() != null) {
            driver.setSafetyCourseNumber(req.safetyCourseNumber().isBlank() ? null : req.safetyCourseNumber().trim());
        }
        driver.setAdrCertValidTo(req.adrCertValidTo());
        driver.setPhone(req.phone());
        // Реквизиты паритета с боевой формой (driver/create).
        driver.setPassport(trimToNull(req.passport()));
        driver.setAddress(trimToNull(req.address()));
        driver.setEmail(trimToNull(req.email()));
        driver.setPowerAttorney(trimToNull(req.powerAttorney()));
        driver.setVisaValidTo(req.visaValidTo());
        driver.setContractNumber(trimToNull(req.contractNumber()));
        driver.setContractValidTo(req.contractValidTo());
        // Закреплённое ТС: если задано — обязано существовать и принадлежать той же организации.
        if (req.assignedVehicleId() != null) {
            var veh = vehicles.findById(req.assignedVehicleId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Закрепляемое ТС не найдено"));
            if (!org.getId().equals(veh.getOrganizationId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "ТС принадлежит другой организации");
            }
            driver.setAssignedVehicleId(veh.getId());
        } else {
            driver.setAssignedVehicleId(null);
        }
        // Блокировку/отстранение водителя ставит/снимает только платформенный админ
        // (Минтранс) — тот же принцип, что и у Vehicle.blocked; перевозчик не может
        // разблокировать своего же водителя в обход регулятора.
        if (currentUser.isPlatformAdmin() && req.suspended() != null) driver.setSuspended(req.suspended());
        // Запись, присланная единой платформой, помечается её источником — по нему в режиме
        // UNIFIED поля e-Transport закрываются от ручной правки.
        if (integrator) driver.setSource("UNIFIED");
        var saved = drivers.save(driver);
        audit.record(existing.isPresent() ? AuditService.UPDATE : AuditService.CREATE,
                "DRIVER", req.rma(), oldName, saved.getFullName());
        return ResponseEntity.status(existing.isPresent() ? HttpStatus.OK : HttpStatus.CREATED).body(saved);
    }

    @PreAuthorize(tj.mintrans.epd.masterdata.config.Authorities.REGISTRY_READ)
    @GetMapping
    public List<Driver> list(@RequestParam(required = false) String rma,
                             @RequestParam(required = false) String organizationRma,
                             @RequestParam(required = false) String q,
                             @RequestParam(defaultValue = "25") int limit) {
        int cap = Math.min(Math.max(limit, 1), 100);
        // Мультиарендность: тенант видит водителей своей организации и (для
        // администратора компании) всех её филиалов. Анонимные вызовы не фильтруются.
        if (tenantScope.isBounded()) {
            var ids = tenantScope.organizationIds();
            if (ids.isEmpty()) {
                return List.of();
            }
            // q — подстрочный поиск по ИНН(РМА) или ФИО с лимитом (тысячи водителей).
            if (q != null) {
                return drivers.searchByOrgs(ids, q.trim(), PageRequest.of(0, cap));
            }
            if (rma != null) {
                return drivers.findByRma(rma)
                        .filter(d -> ids.contains(d.getOrganizationId()))
                        .map(List::of).orElseGet(List::of);
            }
            return drivers.findByOrganizationIdIn(ids);
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

    /**
     * Страница реестра водителей с отбором на сервере. Вместе со строками отдаются госномера
     * закреплённых ТС ({@code id ТС → госномер}) только для этой страницы: без них интерфейс
     * тянул ВЕСЬ справочник ТС (87 МБ) ради одной колонки. Поиск {@code q} — по Ф.И.О., ИНН,
     * табельному номеру, телефону, номеру прав, адресу и названию организации.
     */
    @PreAuthorize(tj.mintrans.epd.masterdata.config.Authorities.REGISTRY_READ)
    @GetMapping("/page")
    public DriverPage page(@RequestParam(defaultValue = "0") int page,
                           @RequestParam(defaultValue = "20") int size,
                           @RequestParam(required = false) String q,
                           @RequestParam(required = false) String organizationRma,
                           @RequestParam(required = false) Short regionId,
                           @RequestParam(required = false) String cityName) {
        var scope = registryQuery.organizationScope(organizationRma, regionId, cityName);
        var spec = registryQuery.<Driver>specification(scope, q,
                List.of("fullName", "rma", "tabNumber", "phone", "licenseNumber", "address"),
                registryQuery.organizationIdsByName(q));
        var result = drivers.findAll(spec, registryQuery.pageable(page, size, "fullName"));
        var vehicleIds = result.getContent().stream()
                .map(Driver::getAssignedVehicleId).filter(java.util.Objects::nonNull).distinct().toList();
        var numbers = new java.util.LinkedHashMap<String, String>();
        for (var v : vehicles.findAllById(vehicleIds)) {
            numbers.put(v.getId().toString(), v.getRegistrationNumber());
        }
        return new DriverPage(result.getContent(), result.getTotalElements(), result.getNumber(),
                result.getSize(), Math.max(1, result.getTotalPages()), numbers);
    }

    /** Страница реестра водителей: строки + госномера закреплённых ТС этой страницы. */
    public record DriverPage(List<Driver> content, long total, int page, int size, int totalPages,
                             java.util.Map<String, String> assignedVehicles) {
    }

    @PreAuthorize(tj.mintrans.epd.masterdata.config.Authorities.REGISTRY_READ)
    @GetMapping("/{id}")
    public Driver get(@PathVariable UUID id) {
        var driver = drivers.findById(id).orElseThrow(() -> new NotFoundException("Водитель не найден"));
        // Мультиарендность: тенант не может прочитать водителя вне своей области по прямому id.
        if (tenantScope.isBounded() && !tenantScope.organizationIds().contains(driver.getOrganizationId())) {
            throw new NotFoundException("Водитель не найден");
        }
        return driver;
    }

    /** Открепление (удаление) водителя от организации. Историю ПЛ не рушит — путевые листы
     *  хранят снимок данных водителя на момент выдачи (master-data и waybill — раздельные БД).
     *  Доступно диспетчеру: ведение состава парка — его повседневная задача. */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','COMPANY_ADMIN','BRANCH_ADMIN','DISPATCHER')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        var driver = drivers.findById(id).orElseThrow(() -> new NotFoundException("Водитель не найден"));
        requireOwnEntity(driver.getOrganizationId());
        // Запись из единой платформы в режиме UNIFIED удаляется/открепляется только там (Настройки → Интеграции).
        sourcePolicy.assertManualDetachAllowed(tj.mintrans.epd.masterdata.service.FormFieldPolicy.DRIVER, driver.getSource());
        drivers.delete(driver);
        audit.record(AuditService.DELETE, "DRIVER", driver.getRma(), driver.getFullName(), null);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------ тенант-защита записи

    /** Тенант пишет в свою организацию либо (администратор компании) в её филиал; иначе 403. */
    private void requireWritable(String organizationRma) {
        if (tenantScope.isBounded() && !tenantScope.canWrite(organizationRma)) {
            throw new AccessDeniedException("Доступ только к своим организациям");
        }
    }

    /** Нельзя «захватить»/изменить сущность, уже закреплённую за другой организацией (409). */
    private void assertNotForeign(UUID existingOrgId, UUID targetOrgId, String what) {
        if (tenantScope.isBounded() && existingOrgId != null && !existingOrgId.equals(targetOrgId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, what + " уже закреплён(а) за другой организацией");
        }
    }

    /** Тенант удаляет/меняет только сущность в своей области (иначе 403). */
    private void requireOwnEntity(UUID entityOrgId) {
        if (tenantScope.isBounded() && !tenantScope.organizationIds().contains(entityOrgId)) {
            throw new AccessDeniedException("Доступ только к своим организациям");
        }
    }

    /** Обрезка пробелов; пустая/только пробелы строка → NULL. */
    private static String trimToNull(String s) {
        if (s == null) return null;
        var t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
