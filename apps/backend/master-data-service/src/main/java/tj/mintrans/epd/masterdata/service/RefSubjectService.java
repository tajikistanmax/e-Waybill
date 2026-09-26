package tj.mintrans.epd.masterdata.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tj.mintrans.epd.masterdata.config.CurrentUser;
import tj.mintrans.epd.masterdata.domain.Driver;
import tj.mintrans.epd.masterdata.domain.Employee;
import tj.mintrans.epd.masterdata.domain.IntegratorUpload;
import tj.mintrans.epd.masterdata.domain.Organization;
import tj.mintrans.epd.masterdata.domain.OrganizationDocument;
import tj.mintrans.epd.masterdata.domain.SubjectDocument;
import tj.mintrans.epd.masterdata.domain.Vehicle;
import tj.mintrans.epd.masterdata.repository.CityRepository;
import tj.mintrans.epd.masterdata.repository.DriverRepository;
import tj.mintrans.epd.masterdata.repository.EmployeeRepository;
import tj.mintrans.epd.masterdata.repository.IntegratorUploadRepository;
import tj.mintrans.epd.masterdata.repository.OrganizationDocumentRepository;
import tj.mintrans.epd.masterdata.repository.OrganizationRepository;
import tj.mintrans.epd.masterdata.repository.SubjectDocumentRepository;
import tj.mintrans.epd.masterdata.repository.VehicleRepository;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Регистрация субъектов внешней системой — совместимость с legacy-каналом КВД ({@code company.jwt:1}):
 * {@code POST/GET organization}, {@code transports}, {@code driver}, {@code employees} и
 * {@code files/upload|delete} (сверка 25.09, G4).
 *
 * <p>Поля и правила — как в legacy {@code Requests/kvd/Store*Request} и DTO. В отличие от внутренних
 * upsert-эндпоинтов платформы, здесь меняются только поля, которые прислала система: остальное в
 * карточке (оплата труда, координаты, прицепы…) не затирается — как у legacy {@code updateOrCreate}.
 * Вложения {@code *_attach} — это {@code file_name}, полученный от {@code files/upload}; при регистрации
 * файл становится документом субъекта со статусом «на проверке», как любой прикреплённый документ.</p>
 */
@Service
public class RefSubjectService {

    /** Готовый legacy-ответ с кодом (ошибки проверки, «не найдено», ошибки файлов). */
    public static class LegacyResponse extends RuntimeException {
        private final HttpStatus status;
        private final Map<String, Object> body;

        public LegacyResponse(HttpStatus status, Map<String, Object> body) {
            super(String.valueOf(body.getOrDefault("error", body.get("message"))));
            this.status = status;
            this.body = body;
        }

        public HttpStatus status() { return status; }
        public Map<String, Object> body() { return body; }
    }

    /** Куда уходит файл вида {@code file_type} (legacy {@code FileUploadController::$map}). */
    record Target(String subject, String docType) {
    }

    static final Map<String, Target> FILE_TYPES = Map.ofEntries(
            Map.entry("registration_certificate_attach", new Target("ORGANIZATION", "REGISTRATION_CERT")),
            Map.entry("aai_attach", new Target("ORGANIZATION", "VAT_CERT")),
            Map.entry("iktibos_attach", new Target("ORGANIZATION", "EXTRACT")),
            Map.entry("license_activity_attach", new Target("ORGANIZATION", "CARRIER_LICENSE")),
            Map.entry("rma_attach", new Target("ORGANIZATION", "TAX_CERT")),
            Map.entry("license_attach", new Target("DRIVER", "DRIVER_LICENSE")),
            Map.entry("passport_attach", new Target("DRIVER", "PASSPORT")),
            Map.entry("duration_lessons_20_hours_attach", new Target("DRIVER", "SAFETY_COURSE")),
            Map.entry("med_cert_valid_date_attach", new Target("DRIVER", "MED_CERT")),
            Map.entry("power_attorney_attach", new Target("DRIVER", "POWER_ATTORNEY")),
            Map.entry("signature_attach_driver", new Target("DRIVER", "SIGNATURE")),
            Map.entry("rma_attach_driver", new Target("DRIVER", "TAX_CERT")),
            Map.entry("photo", new Target("DRIVER", "PHOTO")),
            Map.entry("signature_attach_employee", new Target("EMPLOYEE", "SIGNATURE")),
            Map.entry("tech_id_number_attach", new Target("VEHICLE", "TECH_PASSPORT")),
            Map.entry("tech_inspection_date_attach", new Target("VEHICLE", "TECH_INSPECTION")),
            Map.entry("expire_checklist_date_attach", new Target("VEHICLE", "CONTROL_CARD")));

    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "pdf", "application/pdf", "png", "image/png", "jpg", "image/jpeg", "jpeg", "image/jpeg",
            "doc", "application/msword",
            "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    private static final long MAX_FILE_BYTES = 10_240L * 1024; // legacy max:10240 (КБ)
    private static final int MAX_SUBJECT_DOCUMENTS = 50;
    private static final Pattern RMA = Pattern.compile("^\\d{9,10}$");
    private static final Pattern PLATE = Pattern.compile("^[A-Z0-9]+$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final String INVALID = "The given data was invalid.";

    private final OrganizationRepository organizations;
    private final VehicleRepository vehicles;
    private final DriverRepository drivers;
    private final EmployeeRepository employees;
    private final CityRepository cities;
    private final IntegratorUploadRepository uploads;
    private final OrganizationDocumentRepository orgDocuments;
    private final SubjectDocumentRepository subjectDocuments;
    private final DriverTabNumbers tabNumbers;
    private final CurrentUser currentUser;
    private final AuditService audit;

    public RefSubjectService(OrganizationRepository organizations, VehicleRepository vehicles, DriverRepository drivers,
                             EmployeeRepository employees, CityRepository cities, IntegratorUploadRepository uploads,
                             OrganizationDocumentRepository orgDocuments, SubjectDocumentRepository subjectDocuments,
                             DriverTabNumbers tabNumbers, CurrentUser currentUser, AuditService audit) {
        this.organizations = organizations;
        this.vehicles = vehicles;
        this.drivers = drivers;
        this.employees = employees;
        this.cities = cities;
        this.uploads = uploads;
        this.orgDocuments = orgDocuments;
        this.subjectDocuments = subjectDocuments;
        this.tabNumbers = tabNumbers;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    /** Итог регистрации: ответ и признак «создан» (201) / «обновлён» (200). */
    public record Saved(Map<String, Object> body, boolean created) {
    }

    // =================================================================== организация

    @Transactional
    public Saved storeOrganization(Map<String, Object> in) {
        var f = new Form(in);
        f.required("name").max("name", 255);
        f.required("city_name");
        f.required("registration_certificate");
        f.required("iktibos");
        f.required("rma").rma("rma");
        f.required("address");
        f.required("phone");
        f.required("name_head");
        f.email("email");
        f.required("region_id").in("region_id", 1, 7);
        f.required("type_company_id").in("type_company_id", 1, 2);
        LocalDate licenseFrom = f.date("license_activity_from");
        LocalDate licenseTo = f.date("license_activity_to");
        if ("1".equals(f.str("type_company_id"))) {
            // Общего пользования — лицензия обязательна (legacy OrganizationController::store).
            f.required("license_activity_from").required("license_activity_to");
            if (licenseFrom != null && licenseTo != null && licenseTo.isBefore(licenseFrom)) {
                f.error("license_activity_to", "Дата окончания лицензии не может быть раньше даты начала.");
            }
        }
        f.throwIfInvalid();

        var city = cities.findFirstByNameIgnoreCase(f.str("city_name"))
                .orElseThrow(() -> notFound("City not found", null));
        String rma = f.str("rma");
        var existing = organizations.findByRma(rma);
        List<Attach> attaches = attachments(f, "ORGANIZATION", Map.of(
                "registration_certificate_attach", "registration_certificate_attach",
                "iktibos_attach", "iktibos_attach", "rma_attach", "rma_attach",
                "aai_attach", "aai_attach", "license_activity_attach", "license_activity_attach"));

        String oldName = existing.map(Organization::getName).orElse(null);
        var org = existing.orElseGet(Organization::new);
        org.setRma(rma);
        org.setName(f.str("name"));
        org.setCityName(city.getName());
        org.setRegistrationCertNumber(f.str("registration_certificate"));
        org.setExtractNumber(f.str("iktibos"));
        if (f.has("kpp")) org.setKpp(f.str("kpp"));
        if (f.has("aai")) org.setVatCertNumber(f.str("aai"));
        if (licenseFrom != null) org.setLicenseFrom(licenseFrom);
        if (licenseTo != null) org.setLicenseTo(licenseTo);
        if (f.has("bank")) org.setBank(f.str("bank"));
        org.setAddress(f.str("address"));
        org.setPhone(f.str("phone"));
        org.setNameHead(f.str("name_head"));
        if (f.has("email")) org.setEmail(f.str("email"));
        org.setRegionId(Short.valueOf(f.str("region_id")));
        org.setTypeCompany(Short.parseShort(f.str("type_company_id")));
        var saved = organizations.save(org);
        audit.record(existing.isPresent() ? AuditService.UPDATE : AuditService.CREATE,
                "ORGANIZATION", rma, oldName, saved.getName());
        for (Attach a : attaches) {
            attachToOrganization(saved, a);
        }
        return new Saved(organizationRow(saved), existing.isEmpty());
    }

    public Map<String, Object> listOrganizations(String rma, String kpp, String perPage, String page) {
        if (rma == null || rma.isBlank()) {
            throw invalid("rma", "Параметр rma обязателен.");
        }
        var org = organizations.findByRma(rma.trim())
                .filter(o -> kpp == null || kpp.isBlank() || kpp.trim().equals(o.getKpp()))
                .orElseThrow(() -> notFound("Organization not found", null));
        int size = perPage(perPage, true);
        return paginate(new org.springframework.data.domain.PageImpl<>(List.of(org), PageRequest.of(0, size), 1),
                this::organizationRow);
    }

    // =================================================================== транспорт

    @Transactional
    public Saved storeTransport(Map<String, Object> in) {
        var f = new Form(in);
        Integer type = transportType(f);
        f.required("organization_rma").rma("organization_rma");
        f.required("registration_number").max("registration_number", 15);
        if (f.has("registration_number") && !PLATE.matcher(f.str("registration_number")).matches()) {
            f.error("registration_number", "Госномер — только заглавные латинские буквы и цифры.");
        }
        f.required("brand_name").max("brand_name", 255);
        BigDecimal capacity = f.required("capacity").decimal("capacity");
        Integer counter = f.integer("indication_counter");
        f.max("certificate_number", 50);
        Integer year = f.required("year_manufacture").integer("year_manufacture");
        if (year != null && (year < 1900 || year > LocalDate.now().getYear() + 1)) {
            f.error("year_manufacture", "Год выпуска: 1900–" + (LocalDate.now().getYear() + 1) + ".");
        }
        f.max("vincode", 50);
        Integer conditioner = f.integer("air_conditioner");
        f.max("tech_id_number", 50);
        f.required("tech_inspection_number").max("tech_inspection_number", 50);
        LocalDate inspectionTo = f.required("tech_inspection_date_to").date("tech_inspection_date_to");
        f.max("expire_checklist_number", 50);
        LocalDate checklistTo = f.date("expire_checklist_date_to");
        f.throwIfInvalid();

        var org = organization(f);
        if (org.getTypeCompany() == 1) {
            // Общего пользования — карта контроля обязательна (legacy TransportController::store).
            f.required("expire_checklist_number").required("expire_checklist_date_to");
            f.throwIfInvalid();
        }
        String plate = f.str("registration_number");
        var existing = vehicles.findByRegistrationNumber(plate);
        String vin = f.has("vincode") ? f.str("vincode").toUpperCase(Locale.ROOT) : null;
        if (vin != null && vehicles.findByCanonicalVincode(vin).stream()
                .anyMatch(v -> existing.map(e -> !e.getId().equals(v.getId())).orElse(true))) {
            throw invalid("vincode", "ТС с таким VIN уже зарегистрирован в системе.");
        }
        List<Attach> attaches = attachments(f, "VEHICLE", Map.of(
                "tech_id_number_attach", "tech_id_number_attach",
                "tech_inspection_date_attach", "tech_inspection_date_attach",
                "expire_checklist_date_attach", "expire_checklist_date_attach"));

        String oldBrand = existing.map(Vehicle::getBrand).orElse(null);
        var v = existing.orElseGet(Vehicle::new);
        v.setRegistrationNumber(plate);
        v.setOrganizationId(org.getId());
        v.setTransportType(type.shortValue());
        v.setBrand(f.str("brand_name"));
        v.setCapacity(capacity.setScale(0, java.math.RoundingMode.HALF_UP).intValue());
        if (counter != null) v.setOdometer(counter);
        if (f.has("certificate_number")) v.setCertificateNumber(f.str("certificate_number"));
        v.setYearManufacture(year.shortValue());
        if (vin != null) v.setVincode(vin);
        if (conditioner != null) v.setAirConditioner(conditioner);
        if (f.has("tech_id_number")) v.setTechPassportNumber(f.str("tech_id_number"));
        v.setTechInspectionNumber(f.str("tech_inspection_number"));
        v.setTechInspectionValidTo(inspectionTo);
        if (f.has("expire_checklist_number")) v.setControlCardNumber(f.str("expire_checklist_number"));
        if (checklistTo != null) v.setControlCardValidTo(checklistTo);
        var saved = vehicles.save(v);
        audit.record(existing.isPresent() ? AuditService.UPDATE : AuditService.CREATE,
                "VEHICLE", plate, oldBrand, saved.getBrand());
        for (Attach a : attaches) {
            LocalDate validTo = switch (a.docType()) {
                case "TECH_INSPECTION" -> saved.getTechInspectionValidTo();
                case "CONTROL_CARD" -> saved.getControlCardValidTo();
                default -> null;
            };
            attachToSubject("VEHICLE", saved.getRegistrationNumber(), org.getRma(), a, validTo);
        }
        return new Saved(transportRow(saved, org), existing.isEmpty());
    }

    public Map<String, Object> listTransports(String organizationRma, String kpp, String perPage, String page) {
        var org = organizationForList(organizationRma, kpp);
        Page<Vehicle> p = vehicles.findByOrganizationId(org.getId(), pageRequest(page, perPage, "registrationNumber"));
        return paginate(p, v -> transportRow(v, org));
    }

    // =================================================================== водитель

    @Transactional
    public Saved storeDriver(Map<String, Object> in) {
        var f = new Form(in);
        f.required("full_name").max("full_name", 255);
        f.required("license");
        f.required("category");
        Integer degree = f.integer("degree");
        if (degree != null && (degree < 1 || degree > 3)) {
            f.error("degree", "Класс водителя: 1, 2 или 3.");
        }
        LocalDate medTo = f.date("med_cert_valid_date");
        f.required("rma").rma("rma");
        f.required("address");
        f.required("phone");
        f.email("email");
        f.required("organization_rma").rma("organization_rma");
        f.throwIfInvalid();

        var org = organization(f);
        String rma = f.str("rma");
        var existing = drivers.findByRma(rma);
        List<Attach> attaches = attachments(f, "DRIVER", Map.of(
                "license_attach", "license_attach", "passport_attach", "passport_attach",
                "duration_lessons_20_hours_attach", "duration_lessons_20_hours_attach",
                "med_cert_valid_date_attach", "med_cert_valid_date_attach",
                "rma_attach", "rma_attach_driver", "power_attorney_attach", "power_attorney_attach",
                "photo", "photo", "signature_attach", "signature_attach_driver"));

        String oldName = existing.map(Driver::getFullName).orElse(null);
        var d = existing.orElseGet(Driver::new);
        d.setRma(rma);
        d.setOrganizationId(org.getId());
        d.setTabNumber(tabNumbers.resolve(null, existing, org.getId()));
        d.setFullName(f.str("full_name"));
        d.setLicenseNumber(f.str("license"));
        d.setLicenseCategories(f.str("category"));
        if (degree != null) d.setDegree(degree.shortValue());
        if (f.has("passport")) d.setPassport(f.str("passport"));
        if (f.has("number_lessons_20_hours")) d.setSafetyCourseNumber(f.str("number_lessons_20_hours"));
        // В legacy срок — свободная строка; дату сохраняем, если она распознаётся.
        LocalDate lessonsTo = parseDate(f.str("duration_lessons_20_hours"));
        if (lessonsTo != null) d.setSafetyCourseValidTo(lessonsTo);
        if (f.has("med_cert_number")) d.setMedCertNumber(f.str("med_cert_number"));
        if (medTo != null) d.setMedCertValidTo(medTo);
        if (f.has("contract_number")) d.setContractNumber(f.str("contract_number"));
        LocalDate contractTo = parseDate(f.str("duration_contract_number"));
        if (contractTo != null) d.setContractValidTo(contractTo);
        if (f.has("power_attorney")) d.setPowerAttorney(f.str("power_attorney"));
        d.setAddress(f.str("address"));
        d.setPhone(f.str("phone"));
        if (f.has("email")) d.setEmail(f.str("email"));
        var saved = drivers.save(d);
        audit.record(existing.isPresent() ? AuditService.UPDATE : AuditService.CREATE,
                "DRIVER", rma, oldName, saved.getFullName());
        for (Attach a : attaches) {
            LocalDate validTo = switch (a.docType()) {
                case "MED_CERT" -> saved.getMedCertValidTo();
                case "SAFETY_COURSE" -> saved.getSafetyCourseValidTo();
                default -> null;
            };
            attachToSubject("DRIVER", saved.getRma(), org.getRma(), a, validTo);
        }
        return new Saved(driverRow(saved, org), existing.isEmpty());
    }

    public Map<String, Object> listDrivers(String organizationRma, String kpp, String perPage, String page) {
        var org = organizationForList(organizationRma, kpp);
        Page<Driver> p = drivers.findByOrganizationId(org.getId(), pageRequest(page, perPage, "fullName"));
        return paginate(p, d -> driverRow(d, org));
    }

    // =================================================================== сотрудник

    @Transactional
    public Saved storeEmployee(Map<String, Object> in) {
        var f = new Form(in);
        f.required("name").max("name", 255);
        f.required("rma").rma("rma");
        f.required("type").in("type", 1, 3);
        f.required("organization_rma").rma("organization_rma");
        f.throwIfInvalid();

        var org = organization(f);
        String rma = f.str("rma");
        var existing = employees.findByRma(rma);
        List<Attach> attaches = attachments(f, "EMPLOYEE", Map.of("signature", "signature_attach_employee"));

        String oldName = existing.map(Employee::getName).orElse(null);
        var e = existing.orElseGet(Employee::new);
        e.setRma(rma);
        e.setOrganizationId(org.getId());
        e.setName(f.str("name"));
        e.setType(Short.parseShort(f.str("type")));
        if (f.has("address")) e.setAddress(f.str("address"));
        if (f.has("phone")) e.setPhone(f.str("phone"));
        var saved = employees.save(e);
        audit.record(existing.isPresent() ? AuditService.UPDATE : AuditService.CREATE,
                "EMPLOYEE", rma, oldName, saved.getName());
        for (Attach a : attaches) {
            attachToSubject("EMPLOYEE", saved.getRma(), org.getRma(), a, null);
        }
        return new Saved(employeeRow(saved, org), existing.isEmpty());
    }

    public Map<String, Object> listEmployees(String organizationRma, String kpp, String perPage, String page) {
        var org = organizationForList(organizationRma, kpp);
        Page<Employee> p = employees.findByOrganizationId(org.getId(), pageRequest(page, perPage, "name"));
        return paginate(p, e -> employeeRow(e, org));
    }

    // =================================================================== файлы

    @Transactional
    public Map<String, Object> upload(MultipartFile file, String fileType) {
        Map<String, List<String>> errors = new LinkedHashMap<>();
        String ext = file == null || file.getOriginalFilename() == null ? ""
                : extension(file.getOriginalFilename());
        if (file == null || file.isEmpty()) {
            errors.computeIfAbsent("file", k -> new ArrayList<>()).add("Поле file обязательно.");
        } else {
            if (!CONTENT_TYPES.containsKey(ext)) {
                errors.computeIfAbsent("file", k -> new ArrayList<>()).add("Файл: pdf, png, jpeg, jpg, doc или docx.");
            }
            if (file.getSize() > MAX_FILE_BYTES) {
                errors.computeIfAbsent("file", k -> new ArrayList<>()).add("Файл не больше 10 МБ.");
            }
        }
        if (fileType == null || fileType.isBlank()) {
            errors.computeIfAbsent("file_type", k -> new ArrayList<>()).add("Поле file_type обязательно.");
        }
        if (!errors.isEmpty()) {
            throw new LegacyResponse(HttpStatus.UNPROCESSABLE_ENTITY, body("errors", errors));
        }
        if (!FILE_TYPES.containsKey(fileType.trim())) {
            throw new LegacyResponse(HttpStatus.BAD_REQUEST,
                    body("success", false, "message", "Undefined file_type mapping"));
        }
        String base = baseName(file.getOriginalFilename());
        String name = base + "_" + (System.currentTimeMillis() / 1000) + "." + ext;
        for (int i = 2; uploads.existsByFileName(name); i++) {
            name = base + "_" + (System.currentTimeMillis() / 1000) + "_" + i + "." + ext;
        }
        var u = new IntegratorUpload();
        u.setFileType(fileType.trim());
        u.setFileName(name);
        u.setContentType(CONTENT_TYPES.get(ext));
        u.setSizeBytes(file.getSize());
        try {
            u.setData(file.getBytes());
        } catch (IOException e) {
            throw new LegacyResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                    body("success", false, "message", "Ошибка загрузки", "error", "Не удалось прочитать файл"));
        }
        u.setUploadedBy(currentUser.username().orElse(null));
        uploads.save(u);
        return body("success", true, "message", "Файл успешно загружен",
                "data", body("file_name", name, "file_url", "/md-api/api/v1/ref/files/" + name));
    }

    @Transactional
    public Map<String, Object> delete(String fileType, String fileName) {
        Map<String, List<String>> errors = new LinkedHashMap<>();
        if (fileType == null || fileType.isBlank()) {
            errors.computeIfAbsent("file_type", k -> new ArrayList<>()).add("Поле file_type обязательно.");
        }
        if (fileName == null || fileName.isBlank()) {
            errors.computeIfAbsent("file_name", k -> new ArrayList<>()).add("Поле file_name обязательно.");
        }
        if (!errors.isEmpty()) {
            throw new LegacyResponse(HttpStatus.UNPROCESSABLE_ENTITY, body("errors", errors));
        }
        if (!FILE_TYPES.containsKey(fileType.trim())) {
            throw new LegacyResponse(HttpStatus.BAD_REQUEST,
                    body("success", false, "message", "Undefined file_type mapping"));
        }
        var u = ownUpload(fileName.trim(), fileType.trim())
                .orElseThrow(() -> new LegacyResponse(HttpStatus.NOT_FOUND,
                        body("success", false, "message", "Файл не найден")));
        uploads.delete(u);
        return body("success", true, "message", "Файл удалён", "data", body("file_name", u.getFileName()));
    }

    /** Свой загруженный файл (для file_url). */
    @Transactional(readOnly = true)
    public IntegratorUpload file(String fileName) {
        return uploads.findByFileName(fileName)
                .filter(this::owned)
                .orElseThrow(() -> new LegacyResponse(HttpStatus.NOT_FOUND,
                        body("success", false, "message", "Файл не найден")));
    }

    // =================================================================== вложения

    /** Вложение, найденное до сохранения субъекта (legacy CheckFilesTrait — проверка до записи). */
    record Attach(IntegratorUpload upload, String docType) {
    }

    /**
     * Поля {@code *_attach} запроса → загруженные файлы. Нет файла (или он чужой, или другого вида) —
     * 404 {@code {error, field}}, и субъект не сохраняется.
     */
    private List<Attach> attachments(Form f, String subject, Map<String, String> fieldToFileType) {
        List<Attach> out = new ArrayList<>();
        for (var e : fieldToFileType.entrySet()) {
            if (!f.has(e.getKey())) {
                continue;
            }
            Target t = FILE_TYPES.get(e.getValue());
            var upload = ownUpload(f.str(e.getKey()), e.getValue())
                    .orElseThrow(() -> notFound("Файл не найден", e.getKey()));
            if (!subject.equals(t.subject())) {
                throw notFound("Файл не найден", e.getKey());
            }
            if (VISUAL_TYPES.contains(t.docType()) && !upload.getContentType().startsWith("image/")) {
                throw invalid(e.getKey(), "Фото и подпись — только изображение (png, jpg).");
            }
            out.add(new Attach(upload, t.docType()));
        }
        return out;
    }

    /** Фото и подпись печатаются на бланке — только изображения (как у документов субъектов). */
    private static final java.util.Set<String> VISUAL_TYPES = java.util.Set.of("PHOTO", "SIGNATURE", "SEAL");

    private Optional<IntegratorUpload> ownUpload(String fileName, String fileType) {
        return uploads.findByFileName(fileName)
                .filter(u -> u.getFileType().equals(fileType))
                .filter(this::owned);
    }

    /** Файл виден загрузившей его учётке и администратору платформы. */
    private boolean owned(IntegratorUpload u) {
        return currentUser.isPlatformAdmin()
                || currentUser.username().map(n -> n.equals(u.getUploadedBy())).orElse(false);
    }

    private void attachToOrganization(Organization org, Attach a) {
        var u = a.upload();
        if (u.getUsedAt() != null) {
            return; // уже стал документом при прежней регистрации — не дублируем
        }
        var doc = new OrganizationDocument();
        doc.setOrganizationRma(org.getRma());
        doc.setDocType(a.docType());
        doc.setFileName(u.getFileName());
        doc.setContentType(u.getContentType());
        doc.setSizeBytes(u.getSizeBytes());
        doc.setData(u.getData());
        doc.setStatus("PENDING");
        doc.setUploadedBy(u.getUploadedBy());
        orgDocuments.save(doc);
        u.setUsedAt(OffsetDateTime.now());
        audit.record(AuditService.CREATE, "ORGANIZATION_DOCUMENT", org.getRma(), null,
                "%s · %s · %d байт".formatted(a.docType(), u.getFileName(), u.getSizeBytes()));
    }

    private void attachToSubject(String type, String key, String orgRma, Attach a, LocalDate validTo) {
        var u = a.upload();
        if (u.getUsedAt() != null) {
            return;
        }
        if (subjectDocuments.countBySubjectTypeAndSubjectKey(type, key) >= MAX_SUBJECT_DOCUMENTS) {
            throw invalid("file", "Достигнут предел числа документов объекта (" + MAX_SUBJECT_DOCUMENTS + ").");
        }
        var doc = new SubjectDocument();
        doc.setSubjectType(type);
        doc.setSubjectKey(key);
        doc.setOrganizationRma(orgRma);
        doc.setDocType(a.docType());
        doc.setValidTo(validTo);
        doc.setFileName(u.getFileName());
        doc.setContentType(u.getContentType());
        doc.setSizeBytes(u.getSizeBytes());
        doc.setData(u.getData());
        doc.setStatus("PENDING");
        doc.setUploadedBy(u.getUploadedBy());
        subjectDocuments.save(doc);
        u.setUsedAt(OffsetDateTime.now());
        audit.record(AuditService.CREATE, "SUBJECT_DOCUMENT", type + ":" + key, null,
                "%s · %s · %d байт".formatted(a.docType(), u.getFileName(), u.getSizeBytes()));
    }

    // =================================================================== общее

    /** Организация по {@code organization_rma} (+ {@code organization_kpp}); нет — 404 «Organization not found». */
    private Organization organization(Form f) {
        String kpp = f.str("organization_kpp");
        return organizations.findByRma(f.str("organization_rma"))
                .filter(o -> kpp == null || kpp.equals(o.getKpp()))
                .orElseThrow(() -> notFound("Organization not found", null));
    }

    private Organization organizationForList(String organizationRma, String kpp) {
        if (organizationRma == null || organizationRma.isBlank()) {
            throw invalid("organization_rma", "Параметр organization_rma обязателен.");
        }
        return organizations.findByRma(organizationRma.trim())
                .filter(o -> kpp == null || kpp.isBlank() || kpp.trim().equals(o.getKpp()))
                .orElseThrow(() -> notFound("Organization not found", null));
    }

    /**
     * Тип ТС: {@code transport_type_id} или {@code transport_type} числом 1–6 (в legacy строка
     * {@code transport_type} обязательна, а в карточку идёт {@code transport_type_id}).
     */
    private static Integer transportType(Form f) {
        String raw = f.has("transport_type_id") ? f.str("transport_type_id") : f.str("transport_type");
        if (raw == null) {
            f.error("transport_type", "Параметр transport_type обязателен.");
            return null;
        }
        try {
            int t = Integer.parseInt(raw);
            if (t >= 1 && t <= 6) {
                return t;
            }
        } catch (NumberFormatException ignored) {
            // ниже — общая ошибка
        }
        f.error("transport_type", "Тип ТС: 1 автобус, 2 троллейбус, 3 микроавтобус, 4 легковой, 5 грузовой, 6 грузовой международный.");
        return null;
    }

    private static int perPage(String raw, boolean legacyOrganization) {
        if (raw == null || raw.isBlank()) {
            return 10;
        }
        try {
            int n = Integer.parseInt(raw.trim());
            if (legacyOrganization && n != 10 && n != 20 && n != 100) {
                throw invalid("per_page", "per_page: 10, 20 или 100.");
            }
            return Math.max(1, Math.min(100, n));
        } catch (NumberFormatException e) {
            throw invalid("per_page", "per_page должен быть числом.");
        }
    }

    private static PageRequest pageRequest(String page, String perPage, String sort) {
        int p;
        try {
            p = page == null || page.isBlank() ? 1 : Integer.parseInt(page.trim());
        } catch (NumberFormatException e) {
            throw invalid("page", "page должен быть числом.");
        }
        return PageRequest.of(Math.max(1, p) - 1, perPage(perPage, false), Sort.by(sort, "id"));
    }

    /** Ответ Laravel {@code paginate()}: номер страницы с 1, записи в {@code data}. */
    private static <T> Map<String, Object> paginate(Page<T> p, Function<T, Map<String, Object>> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("current_page", p.getNumber() + 1);
        out.put("data", p.getContent().stream().map(row).toList());
        long from = p.getTotalElements() == 0 ? 0 : (long) p.getNumber() * p.getSize() + 1;
        out.put("from", p.getTotalElements() == 0 ? null : from);
        out.put("last_page", Math.max(1, p.getTotalPages()));
        out.put("per_page", p.getSize());
        out.put("to", p.getTotalElements() == 0 ? null : from + p.getNumberOfElements() - 1);
        out.put("total", p.getTotalElements());
        return out;
    }

    private Map<String, Object> organizationRow(Organization o) {
        return body("id", o.getId(), "name", o.getName(), "rma", o.getRma(), "kpp", o.getKpp(),
                "city_name", o.getCityName(), "region_id", o.getRegionId(), "type_company_id", o.getTypeCompany(),
                "registration_certificate", o.getRegistrationCertNumber(), "iktibos", o.getExtractNumber(),
                "aai", o.getVatCertNumber(), "license_activity_from", o.getLicenseFrom(),
                "license_activity_to", o.getLicenseTo(), "bank", o.getBank(), "address", o.getAddress(),
                "phone", o.getPhone(), "name_head", o.getNameHead(), "email", o.getEmail());
    }

    private static Map<String, Object> orgRef(Organization o) {
        return body("id", o.getId(), "name", o.getName(), "rma", o.getRma(), "kpp", o.getKpp());
    }

    private static Map<String, Object> transportRow(Vehicle v, Organization o) {
        return body("id", v.getId(), "registration_number", v.getRegistrationNumber(),
                "transport_type_id", v.getTransportType(), "brand_name", v.getBrand(), "capacity", v.getCapacity(),
                "indication_counter", v.getOdometer(), "certificate_number", v.getCertificateNumber(),
                "year_manufacture", v.getYearManufacture(), "vincode", v.getVincode(),
                "air_conditioner", v.getAirConditioner(), "tech_id_number", v.getTechPassportNumber(),
                "tech_inspection_number", v.getTechInspectionNumber(),
                "tech_inspection_date_to", v.getTechInspectionValidTo(),
                "expire_checklist_number", v.getControlCardNumber(),
                "expire_checklist_date_to", v.getControlCardValidTo(), "organization", orgRef(o));
    }

    private static Map<String, Object> driverRow(Driver d, Organization o) {
        return body("id", d.getId(), "full_name", d.getFullName(), "rma", d.getRma(),
                "license", d.getLicenseNumber(), "category", d.getLicenseCategories(), "degree", d.getDegree(),
                "passport", d.getPassport(), "number_lessons_20_hours", d.getSafetyCourseNumber(),
                "duration_lessons_20_hours", d.getSafetyCourseValidTo(), "med_cert_number", d.getMedCertNumber(),
                "med_cert_valid_date", d.getMedCertValidTo(), "contract_number", d.getContractNumber(),
                "duration_contract_number", d.getContractValidTo(), "power_attorney", d.getPowerAttorney(),
                "address", d.getAddress(), "phone", d.getPhone(), "email", d.getEmail(),
                "tab_number", d.getTabNumber(), "organization", orgRef(o));
    }

    private static Map<String, Object> employeeRow(Employee e, Organization o) {
        return body("id", e.getId(), "name", e.getName(), "rma", e.getRma(), "type", e.getType(),
                "address", e.getAddress(), "phone", e.getPhone(), "organization", orgRef(o));
    }

    static LegacyResponse invalid(String field, String message) {
        Map<String, List<String>> errors = new LinkedHashMap<>();
        errors.put(field, List.of(message));
        return new LegacyResponse(HttpStatus.UNPROCESSABLE_ENTITY, body("message", INVALID, "errors", errors));
    }

    static LegacyResponse notFound(String error, String field) {
        return new LegacyResponse(HttpStatus.NOT_FOUND,
                field == null ? body("error", error) : body("error", error, "field", field));
    }

    static Map<String, Object> body(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** Имя без расширения, только безопасные символы (как и у документов субъектов). */
    private static String baseName(String original) {
        String n = original == null ? "file" : original.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1);
        int dot = n.lastIndexOf('.');
        if (dot > 0) {
            n = n.substring(0, dot);
        }
        n = n.replaceAll("[^\\p{L}\\p{N}._-]", "_");
        if (n.length() > 120) {
            n = n.substring(0, 120);
        }
        return n.isBlank() ? "file" : n;
    }

    /** Дата в форматах, которые принимает legacy-правило {@code date}: ISO, dd.MM.yyyy, ISO со временем. */
    static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        try {
            if (s.matches("\\d{2}\\.\\d{2}\\.\\d{4}")) {
                return LocalDate.parse(s, DateTimeFormatter.ofPattern("dd.MM.yyyy"));
            }
            return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** Поля запроса и накопленные ошибки — все сразу, как Laravel FormRequest. */
    static final class Form {
        private final Map<String, Object> in;
        private final Map<String, List<String>> errors = new LinkedHashMap<>();

        Form(Map<String, Object> in) {
            this.in = in == null ? Map.of() : in;
        }

        boolean has(String k) {
            return str(k) != null;
        }

        String str(String k) {
            Object v = in.get(k);
            if (v == null) {
                return null;
            }
            String s = v instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())
                    ? String.valueOf(n.longValue()) : v.toString().trim();
            return s.isEmpty() ? null : s;
        }

        void error(String k, String message) {
            errors.computeIfAbsent(k, x -> new ArrayList<>()).add(message);
        }

        private boolean failed(String k) {
            return errors.containsKey(k);
        }

        Form required(String k) {
            if (!has(k) && !failed(k)) {
                error(k, "Параметр " + k + " обязателен.");
            }
            return this;
        }

        Form max(String k, int max) {
            if (has(k) && str(k).length() > max && !failed(k)) {
                error(k, k + ": не более " + max + " символов.");
            }
            return this;
        }

        Form rma(String k) {
            if (has(k) && !RMA.matcher(str(k)).matches() && !failed(k)) {
                error(k, k + " — 9 или 10 цифр.");
            }
            return this;
        }

        Form email(String k) {
            if (has(k) && !EMAIL.matcher(str(k)).matches() && !failed(k)) {
                error(k, k + ": некорректный email.");
            }
            return this;
        }

        Form in(String k, int from, int to) {
            if (has(k) && !failed(k)) {
                try {
                    int v = Integer.parseInt(str(k));
                    if (v < from || v > to) {
                        error(k, k + ": допустимо " + from + "–" + to + ".");
                    }
                } catch (NumberFormatException e) {
                    error(k, k + ": допустимо " + from + "–" + to + ".");
                }
            }
            return this;
        }

        Integer integer(String k) {
            if (!has(k) || failed(k)) {
                return null;
            }
            try {
                return Integer.valueOf(str(k));
            } catch (NumberFormatException e) {
                error(k, k + " должен быть целым числом.");
                return null;
            }
        }

        BigDecimal decimal(String k) {
            if (!has(k) || failed(k)) {
                return null;
            }
            try {
                return new BigDecimal(str(k));
            } catch (NumberFormatException e) {
                error(k, k + " должен быть числом.");
                return null;
            }
        }

        LocalDate date(String k) {
            if (!has(k) || failed(k)) {
                return null;
            }
            LocalDate d = parseDate(str(k));
            if (d == null) {
                error(k, k + " должна быть датой (Y-m-d или d.m.Y).");
            }
            return d;
        }

        void throwIfInvalid() {
            if (!errors.isEmpty()) {
                throw new LegacyResponse(HttpStatus.UNPROCESSABLE_ENTITY, body("message", INVALID, "errors", errors));
            }
        }
    }
}
