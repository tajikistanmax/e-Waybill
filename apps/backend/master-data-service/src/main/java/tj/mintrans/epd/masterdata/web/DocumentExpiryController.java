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

    @GetMapping
    public List<ExpiryItem> list(@RequestParam(defaultValue = "30") int days) {
        LocalDate today = LocalDate.now();
        LocalDate threshold = today.plusDays(days);

        List<Driver> driverList;
        List<Vehicle> vehicleList;
        List<Organization> orgList;

        // Определяем область видимости с учётом мультиарендности.
        if (currentUser.isTenantScoped()) {
            // Не-админ: работаем ТОЛЬКО с его организацией и её водителями/ТС.
            var orgOpt = currentUser.organizationRma().flatMap(organizations::findByRma);
            if (orgOpt.isEmpty()) {
                // РМА не задан или организация не найдена — показывать нечего.
                return List.of();
            }
            Organization org = orgOpt.get();
            driverList = drivers.findByOrganizationId(org.getId());
            vehicleList = vehicles.findByOrganizationId(org.getId());
            orgList = List.of(org);
        } else {
            // Платформенная роль (или анонимный внутренний вызов): все сущности.
            driverList = drivers.findAll();
            vehicleList = vehicles.findAll();
            orgList = organizations.findAll();
        }

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

        // ТС: техосмотр, контрольная карта.
        for (Vehicle v : vehicleList) {
            addItem(result, "VEHICLE", v.getRegistrationNumber(), v.getRegistrationNumber(), "TECH_INSPECTION",
                    v.getTechInspectionValidTo(), today, threshold);
            addItem(result, "VEHICLE", v.getRegistrationNumber(), v.getRegistrationNumber(), "CONTROL_CARD",
                    v.getControlCardValidTo(), today, threshold);
        }

        // Организации: лицензия.
        for (Organization o : orgList) {
            addItem(result, "ORGANIZATION", o.getRma(), o.getName(), "LICENSE",
                    o.getLicenseTo(), today, threshold);
        }

        // Сортировка по дате окончания: сначала самые срочные и уже просроченные.
        result.sort(Comparator.comparing(ExpiryItem::validTo));
        return result;
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
