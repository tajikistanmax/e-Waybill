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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tj.mintrans.epd.masterdata.config.CurrentUser;
import tj.mintrans.epd.masterdata.domain.Vehicle;
import tj.mintrans.epd.masterdata.repository.OrganizationRepository;
import tj.mintrans.epd.masterdata.repository.VehicleRepository;
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

    public VehicleController(VehicleRepository vehicles, OrganizationRepository organizations,
                             CurrentUser currentUser) {
        this.vehicles = vehicles;
        this.organizations = organizations;
        this.currentUser = currentUser;
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
            Short yearManufacture,
            LocalDate techInspectionValidTo,
            LocalDate controlCardValidTo) {
    }

    public record OdometerUpdate(@NotNull Integer odometer) {
    }

    /**
     * Прямой upsert — только push-канал единой платформы (API_INTEGRATOR) и сисадмин.
     * Перевозчики добавляют ТС по госномеру через POST /api/v1/sync/vehicle
     * (марка, VIN, техосмотр — из базы ГАИ).
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('API_INTEGRATOR','SYSTEM_ADMIN')")
    public ResponseEntity<Vehicle> upsert(@Valid @RequestBody VehicleRequest req) {
        var org = organizations.findByRma(req.organizationRma())
                .orElseThrow(() -> new NotFoundException("Организация не найдена"));
        // Госномер канонизируется (обрезка пробелов + верхний регистр), иначе "0114TJ01"
        // и "0114tj01 " создали бы два физически одинаковых ТС и раздвоили бы поиск при выдаче ПЛ.
        var canonicalNumber = canonical(req.registrationNumber());
        var existing = vehicles.findByRegistrationNumber(canonicalNumber);
        var vehicle = existing.orElseGet(Vehicle::new);
        vehicle.setRegistrationNumber(canonicalNumber);
        vehicle.setOrganizationId(org.getId());
        vehicle.setTransportType(req.transportType());
        vehicle.setBrand(req.brand());
        vehicle.setParkingNumber(req.parkingNumber());
        vehicle.setCapacity(req.capacity());
        vehicle.setCarrying(req.carrying());
        if (req.odometer() != null) vehicle.setOdometer(req.odometer());
        vehicle.setVincode(req.vincode());
        vehicle.setYearManufacture(req.yearManufacture());
        vehicle.setTechInspectionValidTo(req.techInspectionValidTo());
        vehicle.setControlCardValidTo(req.controlCardValidTo());
        var saved = vehicles.save(vehicle);
        return ResponseEntity.status(existing.isPresent() ? HttpStatus.OK : HttpStatus.CREATED).body(saved);
    }

    // Межсервисный вызов waybill-service при закрытии ПЛ (permitAll в SecurityConfig).
    // TODO(prod): закрыть client-credentials токеном сервисного аккаунта.
    @PatchMapping("/{id}/odometer")
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

    @GetMapping
    public List<Vehicle> list(@RequestParam(required = false) String registrationNumber,
                              @RequestParam(required = false) String organizationRma) {
        // Поиск по госномеру — в той же канонической форме, что и хранение (регистронезависимо).
        registrationNumber = canonical(registrationNumber);
        // Мультиарендность: не-админ видит только транспорт своей организации.
        // Анонимные (внутренние) вызовы не фильтруются.
        if (currentUser.isTenantScoped()) {
            var org = currentUser.organizationRma().flatMap(organizations::findByRma).orElse(null);
            if (org == null) {
                return List.of();
            }
            if (registrationNumber != null) {
                return vehicles.findByRegistrationNumber(registrationNumber)
                        .filter(v -> org.getId().equals(v.getOrganizationId()))
                        .map(List::of).orElseGet(List::of);
            }
            return vehicles.findByOrganizationId(org.getId());
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

    @GetMapping("/{id}")
    public Vehicle get(@PathVariable UUID id) {
        var vehicle = vehicles.findById(id).orElseThrow(() -> new NotFoundException("Транспорт не найден"));
        // Мультиарендность: не-админ не может прочитать ТС чужой организации по прямому id.
        if (currentUser.isTenantScoped()) {
            var org = currentUser.organizationRma().flatMap(organizations::findByRma).orElse(null);
            if (org == null || !org.getId().equals(vehicle.getOrganizationId())) {
                throw new NotFoundException("Транспорт не найден");
            }
        }
        return vehicle;
    }

    /** Каноническая форма госномера: обрезка пробелов + верхний регистр (null → null). */
    private static String canonical(String registrationNumber) {
        return registrationNumber == null ? null : registrationNumber.trim().toUpperCase();
    }
}
