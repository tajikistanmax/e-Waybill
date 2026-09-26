package tj.mintrans.epd.masterdata.web;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tj.mintrans.epd.masterdata.domain.Driver;
import tj.mintrans.epd.masterdata.domain.Organization;
import tj.mintrans.epd.masterdata.domain.Vehicle;
import tj.mintrans.epd.masterdata.repository.DriverRepository;
import tj.mintrans.epd.masterdata.repository.EmployeeRepository;
import tj.mintrans.epd.masterdata.repository.OrganizationRepository;
import tj.mintrans.epd.masterdata.repository.VehicleRepository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Выгрузки справочников для внешних систем — совместимость с legacy {@code /api/ref/companies|employees|drivers|
 * transports} ({@code CompanyApi\ApiDataController}; сверка 25.09, G1): {@code ?page=1&updated_after=01.09.2026},
 * по 100 записей, ответ {@code {data: [...], meta: {current_page, last_page, per_page, total}}}, поля snake_case.
 *
 * <p>Доступ — сервисный аккаунт интегратора ({@code API_INTEGRATOR}) и администратор платформы. Идентификаторы —
 * UUID платформы (в legacy — числовые); для связи с организацией добавлен её РМА.</p>
 */
@RestController
@RequestMapping("/api/v1/ref")
@PreAuthorize("hasAnyRole('API_INTEGRATOR','SYSTEM_ADMIN')")
public class RefExportController {

    static final int PER_PAGE = 100;
    private static final DateTimeFormatter LEGACY_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final OrganizationRepository organizations;
    private final EmployeeRepository employees;
    private final DriverRepository drivers;
    private final VehicleRepository vehicles;

    public RefExportController(OrganizationRepository organizations, EmployeeRepository employees,
                               DriverRepository drivers, VehicleRepository vehicles) {
        this.organizations = organizations;
        this.employees = employees;
        this.drivers = drivers;
        this.vehicles = vehicles;
    }

    @GetMapping("/companies")
    public Map<String, Object> companies(@RequestParam(required = false) String page,
                                         @RequestParam(name = "updated_after", required = false) String updatedAfter) {
        Page<Organization> p = organizations.findByUpdatedAtAfter(since(updatedAfter), pageable(page));
        return envelope(p, o -> row("id", o.getId(), "name", o.getName(), "rma", o.getRma(), "address", o.getAddress(),
                "name_head", o.getNameHead(), "phone", o.getPhone(), "parent_rma", o.getParentRma(),
                "updated_at", o.getUpdatedAt()));
    }

    /** С {@code organization_rma} — список организации ({@link RefSubjectController}, G4). */
    @GetMapping(value = "/employees", params = "!organization_rma")
    public Map<String, Object> employees(@RequestParam(required = false) String page,
                                         @RequestParam(name = "updated_after", required = false) String updatedAfter) {
        var p = employees.findByUpdatedAtAfter(since(updatedAfter), pageable(page));
        Map<UUID, String> rma = orgRmas(p.getContent().stream().map(e -> e.getOrganizationId()).collect(Collectors.toSet()));
        return envelope(p, e -> row("id", e.getId(), "type", e.getType(), "name", e.getName(), "phone", e.getPhone(),
                "rma", e.getRma(), "company_id", e.getOrganizationId(), "organization_rma", rma.get(e.getOrganizationId()),
                "updated_at", e.getUpdatedAt()));
    }

    @GetMapping("/drivers")
    public Map<String, Object> drivers(@RequestParam(required = false) String page,
                                       @RequestParam(name = "updated_after", required = false) String updatedAfter) {
        Page<Driver> p = drivers.findByUpdatedAtAfter(since(updatedAfter), pageable(page));
        Map<UUID, String> rma = orgRmas(p.getContent().stream().map(Driver::getOrganizationId).collect(Collectors.toSet()));
        return envelope(p, d -> row("id", d.getId(), "full_name", d.getFullName(), "rma", d.getRma(),
                "company_id", d.getOrganizationId(), "organization_rma", rma.get(d.getOrganizationId()),
                "address", d.getAddress(), "phone", d.getPhone(), "updated_at", d.getUpdatedAt()));
    }

    /** С {@code organization_rma} — список организации ({@link RefSubjectController}, G4). */
    @GetMapping(value = "/transports", params = "!organization_rma")
    public Map<String, Object> transports(@RequestParam(required = false) String page,
                                          @RequestParam(name = "updated_after", required = false) String updatedAfter) {
        Page<Vehicle> p = vehicles.findByUpdatedAtAfter(since(updatedAfter), pageable(page));
        Set<UUID> ids = p.getContent().stream().map(Vehicle::getId).collect(Collectors.toSet());
        Map<UUID, List<UUID>> driverIds = new HashMap<>();
        if (!ids.isEmpty()) {
            for (Driver d : drivers.findByAssignedVehicleIdIn(ids)) {
                driverIds.computeIfAbsent(d.getAssignedVehicleId(), k -> new ArrayList<>()).add(d.getId());
            }
        }
        Map<UUID, String> rma = orgRmas(p.getContent().stream().map(Vehicle::getOrganizationId).collect(Collectors.toSet()));
        return envelope(p, v -> row("id", v.getId(), "vincode", v.getVincode(),
                "registration_number", v.getRegistrationNumber(),
                // legacy: transport_type_id 5/6 → «Cargo», иначе «Passenger».
                "transport_type", v.getTransportType() == 5 || v.getTransportType() == 6 ? "Cargo" : "Passenger",
                "model", null, "brand_name", v.getBrand(), "year_manufacture", v.getYearManufacture(),
                "net_weight", null, "imei", null, "gps_tracker_sim_card_number", null,
                "driver_ids", driverIds.getOrDefault(v.getId(), List.of()),
                "organization_rma", rma.get(v.getOrganizationId()), "updated_at", v.getUpdatedAt()));
    }

    // ------------------------------------------------------------------

    /** {@code updated_after} обязателен, как в legacy: {@code dd.MM.yyyy} или ISO {@code yyyy-MM-dd}. */
    static OffsetDateTime since(String updatedAfter) {
        if (updatedAfter == null || updatedAfter.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "updated_after is required (dd.MM.yyyy)");
        }
        String v = updatedAfter.trim();
        LocalDate d;
        try {
            d = v.contains(".") ? LocalDate.parse(v, LEGACY_DATE) : LocalDate.parse(v);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "updated_after must be a date (dd.MM.yyyy)");
        }
        return d.atStartOfDay(ZoneId.systemDefault()).toOffsetDateTime();
    }

    /** {@code page} обязателен и нумеруется с 1 (Laravel paginate). */
    static PageRequest pageable(String page) {
        int n;
        try {
            n = Integer.parseInt(page == null ? "" : page.trim());
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page is required (1, 2, ...)");
        }
        return PageRequest.of(Math.max(1, n) - 1, PER_PAGE, Sort.by("updatedAt", "id"));
    }

    private Map<UUID, String> orgRmas(Set<UUID> ids) {
        ids.remove(null);
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> m = new HashMap<>();
        organizations.findAllById(ids).forEach(o -> m.put(o.getId(), o.getRma()));
        return m;
    }

    private static <T> Map<String, Object> envelope(Page<T> p, Function<T, Map<String, Object>> mapper) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("current_page", p.getNumber() + 1);
        meta.put("last_page", Math.max(1, p.getTotalPages()));
        meta.put("per_page", p.getSize());
        meta.put("total", p.getTotalElements());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("data", p.getContent().stream().map(mapper).toList());
        out.put("meta", meta);
        return out;
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
