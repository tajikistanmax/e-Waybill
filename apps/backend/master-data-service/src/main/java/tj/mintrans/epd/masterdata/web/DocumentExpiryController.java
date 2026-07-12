package tj.mintrans.epd.masterdata.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tj.mintrans.epd.masterdata.config.CurrentUser;
import tj.mintrans.epd.masterdata.domain.Driver;
import tj.mintrans.epd.masterdata.domain.Organization;
import tj.mintrans.epd.masterdata.domain.Vehicle;
import tj.mintrans.epd.masterdata.repository.DriverRepository;
import tj.mintrans.epd.masterdata.repository.OrganizationRepository;
import tj.mintrans.epd.masterdata.repository.VehicleRepository;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Монитор истечения документов — проактивный контроль сроков.
 * Возвращает водителей / ТС / организации, у которых документы истекают
 * в ближайшие N дней (по умолчанию 30) либо уже просрочены.
 * Мультиарендность: не-админ (isTenantScoped) видит только свою организацию;
 * платформенные роли (SYSTEM_ADMIN / MINTRANS_ANALYST / API_INTEGRATOR) — всех.
 */
@RestController
@RequestMapping("/api/v1/document-expiry")
public class DocumentExpiryController {

    private final DriverRepository drivers;
    private final VehicleRepository vehicles;
    private final OrganizationRepository organizations;
    private final CurrentUser currentUser;

    public DocumentExpiryController(DriverRepository drivers,
                                    VehicleRepository vehicles,
                                    OrganizationRepository organizations,
                                    CurrentUser currentUser) {
        this.drivers = drivers;
        this.vehicles = vehicles;
        this.organizations = organizations;
        this.currentUser = currentUser;
    }

    /**
     * Элемент отчёта об истечении.
     *
     * @param entityType тип сущности: DRIVER | VEHICLE | ORGANIZATION
     * @param key        ключ сущности: РМА водителя / госномер ТС / РМА организации
     * @param name       человекочитаемое имя: ФИО / госномер / название
     * @param docType    тип документа: LICENSE | MED_CERT | SAFETY_COURSE | TECH_INSPECTION | CONTROL_CARD
     * @param validTo    дата окончания срока действия
     * @param daysLeft   дней до истечения (отрицательное — документ уже просрочен)
     */
    public record ExpiryItem(
            String entityType,
            String key,
            String name,
            String docType,
            LocalDate validTo,
            long daysLeft) {
    }

    /**
     * Монитор сроков — это операционный инструмент КОНКРЕТНОЙ организации (её автопарк/штат),
     * а не платформенная сводка. Поэтому он всегда работает в рамках ОДНОЙ организации:
     * тенант — своя (из токена); платформенная роль — только с явным organizationRma
     * (иначе пусто: выгружать документы всех организаций одним списком нельзя — при миллионе
     * ТС это неподъёмно и бессмысленно как «алерт»). Результат ограничен limit (самые срочные).
     */
    @GetMapping
    public List<ExpiryItem> list(@RequestParam(defaultValue = "30") int days,
                                 @RequestParam(required = false) String organizationRma,
                                 @RequestParam(defaultValue = "100") int limit) {
        LocalDate today = LocalDate.now();
        LocalDate threshold = today.plusDays(days);
        int cap = Math.min(Math.max(limit, 1), 500);

        // Определяем целевую организацию: тенант — свою; платформенная роль — только по явному РМА.
        Organization org;
        if (currentUser.isTenantScoped()) {
            org = currentUser.organizationRma().flatMap(organizations::findByRma).orElse(null);
        } else {
            org = (organizationRma == null || organizationRma.isBlank())
                    ? null : organizations.findByRma(organizationRma).orElse(null);
        }
        if (org == null) {
            // Тенант без организации ИЛИ платформенная роль без выбранной организации — показывать нечего.
            return List.of();
        }
        List<Driver> driverList = drivers.findByOrganizationId(org.getId());
        List<Vehicle> vehicleList = vehicles.findByOrganizationId(org.getId());
        List<Organization> orgList = List.of(org);

        List<ExpiryItem> result = new ArrayList<>();

        // Водители: ВУ, медсправка, курс БДД.
        for (Driver d : driverList) {
            addItem(result, "DRIVER", d.getRma(), d.getFullName(), "LICENSE",
                    d.getLicenseValidTo(), today, threshold);
            addItem(result, "DRIVER", d.getRma(), d.getFullName(), "MED_CERT",
                    d.getMedCertValidTo(), today, threshold);
            addItem(result, "DRIVER", d.getRma(), d.getFullName(), "SAFETY_COURSE",
                    d.getSafetyCourseValidTo(), today, threshold);
        }

        // ТС: техосмотр, контрольная карта, страховой полис (§13).
        for (Vehicle v : vehicleList) {
            addItem(result, "VEHICLE", v.getRegistrationNumber(), v.getRegistrationNumber(), "TECH_INSPECTION",
                    v.getTechInspectionValidTo(), today, threshold);
            addItem(result, "VEHICLE", v.getRegistrationNumber(), v.getRegistrationNumber(), "CONTROL_CARD",
                    v.getControlCardValidTo(), today, threshold);
            addItem(result, "VEHICLE", v.getRegistrationNumber(), v.getRegistrationNumber(), "INSURANCE",
                    v.getInsuranceValidTo(), today, threshold);
        }

        // Организации: лицензия.
        for (Organization o : orgList) {
            addItem(result, "ORGANIZATION", o.getRma(), o.getName(), "LICENSE",
                    o.getLicenseTo(), today, threshold);
        }

        // Сортировка по дате окончания (сначала самые срочные/просроченные) + ограничение объёма.
        result.sort(Comparator.comparing(ExpiryItem::validTo));
        return result.size() > cap ? new ArrayList<>(result.subList(0, cap)) : result;
    }

    /**
     * Добавляет элемент в отчёт, если срок задан и наступает не позже порога
     * (включая уже просроченные — validTo меньше today).
     */
    private void addItem(List<ExpiryItem> result, String entityType, String key, String name,
                         String docType, LocalDate validTo, LocalDate today, LocalDate threshold) {
        if (validTo == null || validTo.isAfter(threshold)) {
            return;
        }
        long daysLeft = ChronoUnit.DAYS.between(today, validTo);
        result.add(new ExpiryItem(entityType, key, name, docType, validTo, daysLeft));
    }
}
