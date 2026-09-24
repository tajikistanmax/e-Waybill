package tj.mintrans.epd.masterdata.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.persistence.EntityManager;
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
import tj.mintrans.epd.masterdata.config.TenantScope;
import tj.mintrans.epd.masterdata.domain.Organization;
import tj.mintrans.epd.masterdata.repository.OrganizationRepository;
import tj.mintrans.epd.masterdata.service.AuditService;
import tj.mintrans.epd.masterdata.service.FormFieldPolicy;
import tj.mintrans.epd.masterdata.web.error.NotFoundException;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Организации-перевозчики и их филиалы. Upsert по РМА (legacy-семантика rohkhat.tj).
 *
 * <p>Реквизиты организации и структура «компания → филиал» приходят из единой
 * платформы e-Transport (push-канал API_INTEGRATOR) либо заводятся системным
 * администратором. Администратор компании (COMPANY_ADMIN) через этот эндпоинт
 * правит только расчётные параметры своей компании (доля дохода, надбавки за
 * класс, разрешённые виды ПЛ) — идентификационные поля не трогает.</p>
 */
@RestController
@RequestMapping("/api/v1/organizations")
public class OrganizationController {

    private final OrganizationRepository repository;
    private final TenantScope tenantScope;
    private final AuditService audit;
    private final EntityManager em;
    private final FormFieldPolicy formFields;
    private final CurrentUser currentUser;

    public OrganizationController(OrganizationRepository repository,
                                  TenantScope tenantScope, AuditService audit, EntityManager em,
                                  FormFieldPolicy formFields, CurrentUser currentUser) {
        this.repository = repository;
        this.tenantScope = tenantScope;
        this.audit = audit;
        this.em = em;
        this.formFields = formFields;
        this.currentUser = currentUser;
    }

    public record OrganizationRequest(
            @NotBlank @Pattern(regexp = "\\d{9,10}", message = "РМА должен содержать 9–10 цифр") String rma,
            @Pattern(regexp = "\\d{9,10}", message = "РМА головной компании: 9–10 цифр") String parentRma,
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
            LocalDate licenseTo,
            String carrierLicenseNumber,
            // Параметры оплаты труда (§2.6): доля дохода компании и надбавки за класс водителя.
            Double percentIncome,
            Short cat1,
            Short cat2,
            Short cat3,
            // Разрешённые типы ПЛ — CSV имён WaybillType (пусто — без ограничения).
            String allowedWaybillTypes,
            // Реквизиты для паритета с боевой формой company/create (MinTransRT):
            @Min(value = 1, message = "Форма собственности: 1 или 2") @Max(value = 2, message = "Форма собственности: 1 или 2") Short ownership,
            java.math.BigDecimal latitude,
            java.math.BigDecimal longitude,
            String registrationCertNumber,
            String extractNumber,
            String vatCertNumber,
            java.math.BigDecimal planPassVolume,
            java.math.BigDecimal planPassTraffic,
            // Поля карточки старой платформы (V71, решение владельца 22.09 «перенести все поля»):
            // «Рамзи корхона», «Харита», «Сӯзишворӣ».
            @jakarta.validation.constraints.Size(max = 20) String internalNumber,
            @jakarta.validation.constraints.Size(max = 500) String mapPoints,
            Boolean giveFuel) {
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('API_INTEGRATOR','SYSTEM_ADMIN','COMPANY_ADMIN')")
    public ResponseEntity<Organization> upsert(@Valid @RequestBody OrganizationRequest req) {
        var existing = repository.findByRma(req.rma());

        // Администратор компании: только расчётные параметры СВОЕЙ компании, без создания.
        if (tenantScope.isBounded()) {
            if (!tenantScope.canWrite(req.rma())) {
                throw new AccessDeniedException("Доступ только к своей организации");
            }
            var org = existing.orElseThrow(() -> new NotFoundException(
                    "Организация не найдена — её реквизиты заводятся в единой платформе"));
            applyPayroll(org, req);
            var saved = repository.save(org);
            audit.record(AuditService.UPDATE, "ORGANIZATION", req.rma(), org.getName(), saved.getName());
            return ResponseEntity.ok(saved);
        }

        // Обязательные по настройке поля (Настройки → Поля форм) — для ручного ввода администратором.
        // Push-канал единой платформы (API_INTEGRATOR) не проверяется: его набор полей задаёт
        // e-Transport, и настройка формы нашего интерфейса не должна обрывать синхронизацию.
        if (!currentUser.hasRole("API_INTEGRATOR")) {
            formFields.requireFilled(FormFieldPolicy.ORGANIZATION, formValues(req));
        }

        // Платформенная роль / push-канал: полный upsert реквизитов и иерархии.
        String oldName = existing.map(Organization::getName).orElse(null); // до мутации (existing и org — один объект)
        var org = existing.orElseGet(Organization::new);
        org.setRma(req.rma());
        org.setParentRma(normalizeParent(req.parentRma(), req.rma()));
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
        if (req.carrierLicenseNumber() != null) {
            org.setCarrierLicenseNumber(req.carrierLicenseNumber().isBlank() ? null : req.carrierLicenseNumber().trim());
        }
        // Реквизиты паритета с боевой формой (company/create).
        org.setOwnership(req.ownership());
        org.setLatitude(req.latitude());
        org.setLongitude(req.longitude());
        org.setRegistrationCertNumber(orgTrimToNull(req.registrationCertNumber()));
        org.setExtractNumber(orgTrimToNull(req.extractNumber()));
        org.setVatCertNumber(orgTrimToNull(req.vatCertNumber()));
        org.setPlanPassVolume(req.planPassVolume());
        org.setPlanPassTraffic(req.planPassTraffic());
        org.setInternalNumber(orgTrimToNull(req.internalNumber()));
        org.setMapPoints(orgTrimToNull(req.mapPoints()));
        org.setGiveFuel(Boolean.TRUE.equals(req.giveFuel()));
        applyPayroll(org, req);
        var saved = repository.save(org);
        audit.record(existing.isPresent() ? AuditService.UPDATE : AuditService.CREATE,
                "ORGANIZATION", req.rma(), oldName, saved.getName());
        return ResponseEntity.status(existing.isPresent() ? HttpStatus.OK : HttpStatus.CREATED).body(saved);
    }

    /** Расчётные параметры компании (§2.6) — правит и администратор компании. */
    private static void applyPayroll(Organization org, OrganizationRequest req) {
        if (req.percentIncome() != null) org.setPercentIncome(req.percentIncome());
        if (req.cat1() != null) org.setCat1(req.cat1());
        if (req.cat2() != null) org.setCat2(req.cat2());
        if (req.cat3() != null) org.setCat3(req.cat3());
        if (req.allowedWaybillTypes() != null) {
            org.setAllowedWaybillTypes(req.allowedWaybillTypes().isBlank() ? null : req.allowedWaybillTypes().trim());
        }
    }

    /** Значения полей формы организации по ключам {@link FormFieldPolicy} (для проверки обязательности). */
    static Map<String, Object> formValues(OrganizationRequest req) {
        Map<String, Object> v = new HashMap<>();
        v.put("rma", req.rma());
        v.put("name", req.name());
        v.put("kpp", req.kpp());
        v.put("internalNumber", req.internalNumber());
        v.put("typeCompany", req.typeCompany());
        v.put("regionId", req.regionId());
        v.put("cityName", req.cityName());
        v.put("address", req.address());
        v.put("phone", req.phone());
        v.put("email", req.email());
        v.put("nameHead", req.nameHead());
        v.put("bank", req.bank());
        v.put("licenseFrom", req.licenseFrom());
        v.put("licenseTo", req.licenseTo());
        v.put("carrierLicenseNumber", req.carrierLicenseNumber());
        v.put("percentIncome", req.percentIncome());
        v.put("cat1", req.cat1());
        v.put("cat2", req.cat2());
        v.put("cat3", req.cat3());
        v.put("ownership", req.ownership());
        v.put("registrationCertNumber", req.registrationCertNumber());
        v.put("extractNumber", req.extractNumber());
        v.put("vatCertNumber", req.vatCertNumber());
        v.put("planPassVolume", req.planPassVolume());
        v.put("planPassTraffic", req.planPassTraffic());
        v.put("latitude", req.latitude());
        v.put("longitude", req.longitude());
        v.put("mapPoints", req.mapPoints());
        v.put("giveFuel", req.giveFuel());
        v.put("allowedWaybillTypes", req.allowedWaybillTypes());
        return v;
    }

    /** Обрезка пробелов; пустая/только пробелы строка → NULL. */
    private static String orgTrimToNull(String s) {
        if (s == null) return null;
        var t = s.trim();
        return t.isEmpty() ? null : t;
    }

    public record BlockRequest(@NotBlank String reason) {
    }

    /**
     * Блокировка организации — запрет новых ПЛ (§4). Раньше поле {@code blocked} менялось
     * общим upsert реквизитов без обязательной причины, а запись аудита фиксировала только
     * диф имени организации — расследовать «кто и почему заблокировал» было невозможно.
     * Причина обязательна (как у блокировки ПЛ инспектором) и хранится на самой организации —
     * COMPANY_ADMIN видит её без доступа к аудит-журналу платформы.
     */
    @PostMapping("/{id}/block")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public Organization block(@PathVariable UUID id, @Valid @RequestBody BlockRequest req) {
        var org = repository.findById(id).orElseThrow(() -> new NotFoundException("Организация не найдена"));
        org.setBlocked(true);
        org.setBlockReason(req.reason());
        var saved = repository.save(org);
        audit.record(AuditService.UPDATE, "ORGANIZATION", org.getRma(), "активна", "заблокирована: " + req.reason());
        return saved;
    }

    @PostMapping("/{id}/unblock")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public Organization unblock(@PathVariable UUID id) {
        var org = repository.findById(id).orElseThrow(() -> new NotFoundException("Организация не найдена"));
        String oldReason = org.getBlockReason();
        org.setBlocked(false);
        org.setBlockReason(null);
        var saved = repository.save(org);
        audit.record(AuditService.UPDATE, "ORGANIZATION", org.getRma(),
                "заблокирована: " + (oldReason == null ? "—" : oldReason), "активна");
        return saved;
    }

    /** Филиал не может ссылаться сам на себя. */
    private static String normalizeParent(String parentRma, String ownRma) {
        if (parentRma == null || parentRma.isBlank() || parentRma.equals(ownRma)) {
            return null;
        }
        return parentRma.trim();
    }

    @GetMapping
    public List<Organization> list(@RequestParam(required = false) String rma) {
        // Мультиарендность: администратор компании видит свою организацию и все её филиалы;
        // прочие тенант-роли — только свою. Анонимные (внутренние) вызовы не фильтруются.
        if (tenantScope.isBounded()) {
            var scoped = tenantScope.organizations();
            if (rma != null) {
                return scoped.stream().filter(o -> rma.equals(o.getRma())).toList();
            }
            return scoped;
        }
        if (rma != null) {
            return repository.findByRma(rma).map(List::of).orElseGet(List::of);
        }
        return repository.findAll();
    }

    /** Счётчики ТС/водителей/сотрудников по организации. */
    public record OrgCounts(String rma, long vehicles, long drivers, long employees) {
    }

    /**
     * Счётчики ТС/водителей/сотрудников по ВСЕМ организациям одним запросом (3 GROUP BY).
     * Заменяет N+1 со страницы «Компания» (там на боевом объёме в 400+ орг это упирало
     * в rate-limit — по 3 запроса на каждую организацию). Мультиарендность: тенант получает
     * счётчики только своих организаций.
     */
    @GetMapping("/counts")
    public List<OrgCounts> counts() {
        Map<String, String> idToRma = new HashMap<>();
        @SuppressWarnings("unchecked")
        List<Object[]> orgs = em.createNativeQuery("SELECT id::text, rma FROM organization").getResultList();
        for (Object[] r : orgs) {
            idToRma.put((String) r[0], (String) r[1]);
        }

        Map<String, long[]> byRma = new HashMap<>(); // rma -> [vehicles, drivers, employees]
        addCounts("SELECT organization_id::text, count(*) FROM vehicle WHERE organization_id IS NOT NULL GROUP BY organization_id", 0, idToRma, byRma);
        addCounts("SELECT organization_id::text, count(*) FROM driver WHERE organization_id IS NOT NULL GROUP BY organization_id", 1, idToRma, byRma);
        addCounts("SELECT organization_id::text, count(*) FROM employee WHERE organization_id IS NOT NULL GROUP BY organization_id", 2, idToRma, byRma);

        Set<String> allowed = tenantScope.isBounded()
                ? tenantScope.organizations().stream().map(Organization::getRma).collect(Collectors.toSet())
                : null;

        return byRma.entrySet().stream()
                .filter(e -> allowed == null || allowed.contains(e.getKey()))
                .map(e -> new OrgCounts(e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2]))
                .toList();
    }

    private void addCounts(String sql, int idx, Map<String, String> idToRma, Map<String, long[]> byRma) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(sql).getResultList();
        for (Object[] r : rows) {
            String rma = idToRma.get((String) r[0]);
            if (rma == null) continue; // организация-сирота — пропускаем
            long n = ((Number) r[1]).longValue();
            byRma.computeIfAbsent(rma, k -> new long[3])[idx] += n;
        }
    }

    /** Филиалы головной компании (для платформенных ролей и token-relay из waybill-service). */
    @GetMapping("/children")
    public List<Organization> children(@RequestParam String parentRma) {
        if (tenantScope.isBounded() && !tenantScope.contains(parentRma)) {
            return List.of();
        }
        return repository.findByParentRma(parentRma);
    }

    @GetMapping("/{id}")
    public Organization get(@PathVariable UUID id) {
        var org = repository.findById(id).orElseThrow(() -> new NotFoundException("Организация не найдена"));
        // Мультиарендность: тенант читает только организации своей области.
        if (tenantScope.isBounded() && !tenantScope.contains(org.getRma())) {
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
}
