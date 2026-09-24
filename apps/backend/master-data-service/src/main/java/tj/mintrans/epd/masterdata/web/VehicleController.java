package tj.mintrans.epd.masterdata.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tj.mintrans.epd.masterdata.config.CurrentUser;
import tj.mintrans.epd.masterdata.config.TenantScope;
import tj.mintrans.epd.masterdata.domain.Vehicle;
import tj.mintrans.epd.masterdata.repository.OrganizationRepository;
import tj.mintrans.epd.masterdata.repository.VehicleRepository;
import tj.mintrans.epd.masterdata.service.AuditService;
import tj.mintrans.epd.masterdata.service.FormFieldPolicy;
import tj.mintrans.epd.masterdata.web.error.NotFoundException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Транспортные средства. Upsert по государственному номеру (legacy-семантика).
 */
@RestController
@RequestMapping("/api/v1/vehicles")
public class VehicleController {

    private final VehicleRepository vehicles;
    private final OrganizationRepository organizations;
    private final CurrentUser currentUser;
    private final TenantScope tenantScope;
    private final AuditService audit;
    private final tj.mintrans.epd.masterdata.service.VehicleCardRules cardRules;
    private final tj.mintrans.epd.masterdata.service.RegistryQuery registryQuery;
    private final FormFieldPolicy formFields;

    public VehicleController(VehicleRepository vehicles, OrganizationRepository organizations,
                             CurrentUser currentUser, TenantScope tenantScope, AuditService audit,
                             tj.mintrans.epd.masterdata.service.VehicleCardRules cardRules,
                             tj.mintrans.epd.masterdata.service.RegistryQuery registryQuery,
                             FormFieldPolicy formFields) {
        this.vehicles = vehicles;
        this.organizations = organizations;
        this.currentUser = currentUser;
        this.tenantScope = tenantScope;
        this.audit = audit;
        this.cardRules = cardRules;
        this.registryQuery = registryQuery;
        this.formFields = formFields;
    }

    public record VehicleRequest(
            @NotBlank @Pattern(regexp = "[A-Za-zА-Яа-я0-9]{4,20}", message = "Госномер: буквы и цифры") String registrationNumber,
            @NotBlank @Pattern(regexp = "\\d{9,10}", message = "РМА организации должен содержать 9–10 цифр") String organizationRma,
            @NotNull @Min(value = 1, message = "Тип ТС: 1–6") @Max(value = 6, message = "Тип ТС: 1–6") Short transportType,
            String brand,
            @Pattern(regexp = "\\d{4}", message = "Номер стоянки — 4 цифры") String parkingNumber,
            Integer capacity,
            BigDecimal carrying,
            Integer odometer,
            String vincode,
            @Min(value = 1, message = "Вид топлива: 1–5") @Max(value = 5, message = "Вид топлива: 1–5") Short fuelType,
            @Min(value = 0, message = "Мощность: 0–3000 л.с.") @Max(value = 3000, message = "Мощность: 0–3000 л.с.") Integer enginePower,
            Short yearManufacture,
            LocalDate techInspectionValidTo,
            LocalDate controlCardValidTo,
            String controlCardNumber,
            String intlCertificateNumber,
            LocalDate insuranceValidTo,
            LocalDate adrApprovalValidTo,
            // Реквизиты для паритета с боевой формой parking/create (MinTransRT):
            String techInspectionNumber,
            String techPassportNumber,
            String certificateNumber,
            @Min(value = 0, message = "Кондиционер: 0–100%") @Max(value = 100, message = "Кондиционер: 0–100%") Integer airConditioner,
            String intlControlCardNumber,
            LocalDate intlControlCardValidTo,
            String trailer1Number,
            String trailer1Brand,
            BigDecimal trailer1Carrying,
            BigDecimal trailer1Weight,
            String trailer2Number,
            String trailer2Brand,
            BigDecimal trailer2Carrying,
            BigDecimal trailer2Weight,
            Boolean blocked) {
    }

    public record OdometerUpdate(@NotNull Integer odometer) {
    }

    /**
     * Нативное управление ТС внутри платформы: перевозчик (COMPANY_ADMIN/DISPATCHER) ведёт ТС
     * СВОЕЙ организации; push-канал единой платформы (API_INTEGRATOR) и сисадмин — любые.
     * Тенант не пишет в чужую организацию (403) и не «захватывает» ТС по госномеру (409).
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('API_INTEGRATOR','SYSTEM_ADMIN','COMPANY_ADMIN','BRANCH_ADMIN','DISPATCHER')")
    public ResponseEntity<Vehicle> upsert(@Valid @RequestBody VehicleRequest req) {
        requireWritable(req.organizationRma());
        // Обязательные по настройке поля (Настройки → Поля транспорта) — для ручного ввода;
        // push-канал единой платформы (API_INTEGRATOR) не проверяется, как и у организации.
        if (!currentUser.hasRole("API_INTEGRATOR")) {
            formFields.requireFilled(FormFieldPolicy.VEHICLE, req);
        }
        var org = organizations.findByRma(req.organizationRma())
                .orElseThrow(() -> new NotFoundException("Организация не найдена"));
        // Госномер канонизируется (обрезка пробелов + верхний регистр), иначе "0114TJ01"
        // и "0114tj01 " создали бы два физически одинаковых ТС и раздвоили бы поиск при выдаче ПЛ.
        var canonicalNumber = canonical(req.registrationNumber());
        // Легковые (тип 4): строгий формат госномера legacy 234AB01 / 1234AB01 (MIGRATION.md 12.2) — 422.
        tj.mintrans.epd.masterdata.service.VehicleCardRules.assertPlateFormat(req.transportType(), canonicalNumber);
        var existing = vehicles.findByRegistrationNumber(canonicalNumber);
        assertNotForeign(existing.map(Vehicle::getOrganizationId).orElse(null), org.getId(), "ТС");
        String oldBrand = existing.map(Vehicle::getBrand).orElse(null); // до мутации (existing и vehicle — один объект)
        var vehicle = existing.orElseGet(Vehicle::new);
        vehicle.setRegistrationNumber(canonicalNumber);
        vehicle.setOrganizationId(org.getId());
        vehicle.setTransportType(req.transportType());
        vehicle.setBrand(req.brand());
        // Номер стоянки уникален в организации (legacy ParkingRequest, MIGRATION.md 12.13) — 409 до save.
        cardRules.assertParkingNumberUnique(org.getId(), req.parkingNumber(), vehicle);
        vehicle.setParkingNumber(req.parkingNumber());
        vehicle.setCapacity(req.capacity());
        vehicle.setCarrying(req.carrying());
        if (req.odometer() != null) vehicle.setOdometer(req.odometer());
        // VIN канонизируется симметрично госномеру (trim + верхний регистр; пустой → NULL) и, если
        // задан, обязан быть уникальным (частичный индекс uq_vehicle_vincode, V49). Проверяем ДО save,
        // чтобы вернуть внятный 409, а не generic «конфликт целостности» из обработчика БД.
        var canonicalVin = canonicalVin(req.vincode());
        assertVinUnique(canonicalVin, vehicle);
        vehicle.setVincode(canonicalVin);
        vehicle.setFuelType(req.fuelType());
        vehicle.setEnginePower(req.enginePower());
        // Год выпуска 1900…текущий+1 (legacy kvd/StoreTransportRequest, MIGRATION.md 12.3) — 422.
        cardRules.assertYearManufacture(req.yearManufacture());
        vehicle.setYearManufacture(req.yearManufacture());
        vehicle.setTechInspectionValidTo(req.techInspectionValidTo());
        vehicle.setControlCardValidTo(req.controlCardValidTo());
        if (req.controlCardNumber() != null) {
            vehicle.setControlCardNumber(req.controlCardNumber().isBlank() ? null : req.controlCardNumber().trim());
        }
        if (req.intlCertificateNumber() != null) {
            vehicle.setIntlCertificateNumber(req.intlCertificateNumber().isBlank() ? null : req.intlCertificateNumber().trim());
        }
        vehicle.setInsuranceValidTo(req.insuranceValidTo());
        vehicle.setAdrApprovalValidTo(req.adrApprovalValidTo());
        // Реквизиты паритета с боевой формой (parking/create): номера документов, кондиционер, прицепы.
        vehicle.setTechInspectionNumber(trimToNull(req.techInspectionNumber()));
        vehicle.setTechPassportNumber(trimToNull(req.techPassportNumber()));
        vehicle.setCertificateNumber(trimToNull(req.certificateNumber()));
        vehicle.setAirConditioner(req.airConditioner());
        vehicle.setIntlControlCardNumber(trimToNull(req.intlControlCardNumber()));
        vehicle.setIntlControlCardValidTo(req.intlControlCardValidTo());
        vehicle.setTrailer1Number(trimToNull(req.trailer1Number()));
        vehicle.setTrailer1Brand(trimToNull(req.trailer1Brand()));
        vehicle.setTrailer1Carrying(req.trailer1Carrying());
        vehicle.setTrailer1Weight(req.trailer1Weight());
        vehicle.setTrailer2Number(trimToNull(req.trailer2Number()));
        vehicle.setTrailer2Brand(trimToNull(req.trailer2Brand()));
        vehicle.setTrailer2Carrying(req.trailer2Carrying());
        vehicle.setTrailer2Weight(req.trailer2Weight());
        // Блокировку ТС ставит/снимает только платформенный админ (Минтранс); перевозчик — нет.
        if (currentUser.isPlatformAdmin() && req.blocked() != null) vehicle.setBlocked(req.blocked());
        var saved = vehicles.save(vehicle);
        audit.record(existing.isPresent() ? AuditService.UPDATE : AuditService.CREATE,
                "VEHICLE", canonicalNumber, oldBrand, saved.getBrand());
        return ResponseEntity.status(existing.isPresent() ? HttpStatus.OK : HttpStatus.CREATED).body(saved);
    }

    // Межсервисный вызов waybill-service при закрытии ПЛ — только сервисный аккаунт
    // (API_INTEGRATOR, client-credentials токен) или сисадмин. Иначе любой авторизованный
    // мог бы монотонно завышать одометр чужого ТС.
    @PatchMapping("/{id}/odometer")
    @PreAuthorize("hasAnyRole('API_INTEGRATOR','SYSTEM_ADMIN')")
    public Vehicle updateOdometer(@PathVariable UUID id, @Valid @RequestBody OdometerUpdate req) {
        var vehicle = vehicles.findById(id).orElseThrow(() -> new NotFoundException("Транспорт не найден"));
        if (req.odometer() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Одометр не может быть отрицательным");
        }
        // Непрерывность пробега: одометр не должен уменьшаться относительно последнего значения.
        if (req.odometer() < vehicle.getOdometer()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Одометр не может уменьшаться (текущий: " + vehicle.getOdometer() + ")");
        }
        vehicle.setOdometer(req.odometer());
        return vehicles.save(vehicle);
    }

    @PreAuthorize(tj.mintrans.epd.masterdata.config.Authorities.REGISTRY_READ)
    @GetMapping
    public List<Vehicle> list(@RequestParam(required = false) String registrationNumber,
                              @RequestParam(required = false) String organizationRma,
                              @RequestParam(required = false) String q,
                              @RequestParam(defaultValue = "25") int limit) {
        // Поиск по госномеру — в той же канонической форме, что и хранение (регистронезависимо).
        registrationNumber = canonical(registrationNumber);
        int cap = Math.min(Math.max(limit, 1), 100);
        // Мультиарендность: тенант видит транспорт своей организации и (для
        // администратора компании) всех её филиалов. Анонимные вызовы не фильтруются.
        if (tenantScope.isBounded()) {
            var ids = tenantScope.organizationIds();
            if (ids.isEmpty()) {
                return List.of();
            }
            // q — подстрочный поиск по госномеру с лимитом (автопарки в тысячи ТС).
            if (q != null) {
                return vehicles.searchByOrgs(ids, q.trim(), PageRequest.of(0, cap));
            }
            if (registrationNumber != null) {
                return vehicles.findByRegistrationNumber(registrationNumber)
                        .filter(v -> ids.contains(v.getOrganizationId()))
                        .map(List::of).orElseGet(List::of);
            }
            return vehicles.findByOrganizationIdIn(ids);
        }
        // Платформенная роль: подстрочный поиск в пределах указанной организации.
        if (q != null && organizationRma != null) {
            var org = organizations.findByRma(organizationRma).orElse(null);
            return org == null ? List.of() : vehicles.searchByOrg(org.getId(), q.trim(), PageRequest.of(0, cap));
        }
        if (registrationNumber != null) {
            return vehicles.findByRegistrationNumber(registrationNumber).map(List::of).orElseGet(List::of);
        }
        if (organizationRma != null) {
            return organizations.findByRma(organizationRma)
                    .map(org -> vehicles.findByOrganizationId(org.getId()))
                    .orElseGet(List::of);
        }
        return vehicles.findAll();
    }

    /**
     * Постраничный реестр ТС с отбором на сервере. Заменяет выгрузку всей таблицы в браузер:
     * у администратора платформы {@code GET /api/v1/vehicles} возвращал 89 287 ТС — 87 МБ за 42 с
     * (находка приёмки 22.09.2026). Поиск {@code q} — по госномеру, марке, VIN, номеру стоянки
     * и названию организации; {@code regionId}/{@code cityName} — география организации-владельца.
     */
    @PreAuthorize(tj.mintrans.epd.masterdata.config.Authorities.REGISTRY_READ)
    @GetMapping("/page")
    public PagedResult<Vehicle> page(@RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "20") int size,
                                     @RequestParam(required = false) String q,
                                     @RequestParam(required = false) String organizationRma,
                                     @RequestParam(required = false) Short transportType,
                                     @RequestParam(required = false) Short regionId,
                                     @RequestParam(required = false) String cityName) {
        var scope = registryQuery.organizationScope(organizationRma, regionId, cityName);
        var spec = registryQuery.<Vehicle>specification(scope, q,
                List.of("registrationNumber", "brand", "vincode", "parkingNumber"),
                registryQuery.organizationIdsByName(q));
        if (transportType != null) {
            spec = spec.and((root, cq, cb) -> cb.equal(root.get("transportType"), transportType));
        }
        return PagedResult.of(vehicles.findAll(spec, registryQuery.pageable(page, size, "registrationNumber")));
    }

    /**
     * Число ТС по организациям ({@code РМА → количество}), необязательно — только вида
     * {@code transportType} (1..6). Одна агрегатная выборка для отчёта «Норматив выдачи ПЛ»
     * (waybill-service) вместо списка ТС каждой организации. Тенант получает только свою область.
     */
    @PreAuthorize(tj.mintrans.epd.masterdata.config.Authorities.REGISTRY_READ)
    @GetMapping("/count-by-organization")
    public java.util.Map<String, Long> countByOrganization(
            @RequestParam(required = false) Short transportType) {
        List<Object[]> rows = transportType == null
                ? vehicles.countByOrganization()
                : vehicles.countByOrganizationForType(transportType);
        java.util.Set<UUID> allowed = tenantScope.isBounded() ? new java.util.HashSet<>(tenantScope.organizationIds()) : null;
        java.util.Map<UUID, Long> byId = new java.util.LinkedHashMap<>();
        for (Object[] r : rows) {
            UUID orgId = (UUID) r[0];
            if (orgId == null || (allowed != null && !allowed.contains(orgId))) {
                continue;
            }
            byId.put(orgId, ((Number) r[1]).longValue());
        }
        java.util.Map<String, Long> byRma = new java.util.LinkedHashMap<>();
        for (var org : organizations.findAllById(byId.keySet())) {
            if (org.getRma() != null) {
                byRma.put(org.getRma(), byId.get(org.getId()));
            }
        }
        return byRma;
    }

    @PreAuthorize(tj.mintrans.epd.masterdata.config.Authorities.REGISTRY_READ)
    @GetMapping("/{id}")
    public Vehicle get(@PathVariable UUID id) {
        var vehicle = vehicles.findById(id).orElseThrow(() -> new NotFoundException("Транспорт не найден"));
        // Мультиарендность: тенант не может прочитать ТС вне своей области по прямому id.
        if (tenantScope.isBounded() && !tenantScope.organizationIds().contains(vehicle.getOrganizationId())) {
            throw new NotFoundException("Транспорт не найден");
        }
        return vehicle;
    }

    /** Открепление (удаление) ТС от организации. Историю ПЛ не рушит — путевые листы хранят
     *  снимок ТС. Доступно диспетчеру — ведение состава парка его повседневная задача. */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','COMPANY_ADMIN','BRANCH_ADMIN','DISPATCHER')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        var vehicle = vehicles.findById(id).orElseThrow(() -> new NotFoundException("Транспорт не найден"));
        requireOwnEntity(vehicle.getOrganizationId());
        vehicles.delete(vehicle);
        audit.record(AuditService.DELETE, "VEHICLE", vehicle.getRegistrationNumber(), vehicle.getBrand(), null);
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

    /** Каноническая форма госномера: обрезка пробелов + верхний регистр (null → null). */
    private static String canonical(String registrationNumber) {
        return registrationNumber == null ? null : registrationNumber.trim().toUpperCase();
    }

    /** Обрезка пробелов; пустая/только пробелы строка → NULL (чтобы не хранить ""). */
    private static String trimToNull(String s) {
        if (s == null) return null;
        var t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** Каноническая форма VIN: обрезка пробелов + верхний регистр; пустой/только пробелы → NULL
     *  (пустой VIN разрешён и не участвует в проверке уникальности). */
    private static String canonicalVin(String vincode) {
        if (vincode == null) return null;
        var trimmed = vincode.trim();
        return trimmed.isEmpty() ? null : trimmed.toUpperCase();
    }

    /** Уникальность VIN (симметрично госномеру): если канонический VIN задан и уже принадлежит
     *  ДРУГОМУ ТС — 409, а не 500 от нарушения индекса uq_vehicle_vincode. Пустой VIN не проверяем. */
    private void assertVinUnique(String canonicalVin, Vehicle current) {
        if (canonicalVin == null) return;
        boolean takenByOther = vehicles.findByCanonicalVincode(canonicalVin).stream()
                .anyMatch(v -> !v.getId().equals(current.getId()));
        if (takenByOther) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "ТС с таким VIN уже зарегистрирован в системе");
        }
    }
}
