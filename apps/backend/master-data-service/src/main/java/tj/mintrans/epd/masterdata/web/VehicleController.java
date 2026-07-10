package tj.mintrans.epd.masterdata.web;

import jakarta.validation.Valid;
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

    public VehicleController(VehicleRepository vehicles, OrganizationRepository organizations) {
        this.vehicles = vehicles;
        this.organizations = organizations;
    }

    public record VehicleRequest(
            @NotBlank @Pattern(regexp = "[A-Za-zА-Яа-я0-9]{4,20}", message = "Госномер: буквы и цифры") String registrationNumber,
            @NotBlank @Pattern(regexp = "\\d{9,10}", message = "РМА организации должен содержать 9–10 цифр") String organizationRma,
            @NotNull Short transportType,
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

    @PostMapping
    @PreAuthorize("hasAnyRole('COMPANY_ADMIN','SYSTEM_ADMIN')")
    public ResponseEntity<Vehicle> upsert(@Valid @RequestBody VehicleRequest req) {
        var org = organizations.findByRma(req.organizationRma())
                .orElseThrow(() -> new NotFoundException("Организация не найдена"));
        var existing = vehicles.findByRegistrationNumber(req.registrationNumber());
        var vehicle = existing.orElseGet(Vehicle::new);
        vehicle.setRegistrationNumber(req.registrationNumber());
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

    @PatchMapping("/{id}/odometer")
    @PreAuthorize("hasAnyRole('COMPANY_ADMIN','SYSTEM_ADMIN')")
    public Vehicle updateOdometer(@PathVariable UUID id, @Valid @RequestBody OdometerUpdate req) {
        var vehicle = vehicles.findById(id).orElseThrow(() -> new NotFoundException("Транспорт не найден"));
        vehicle.setOdometer(req.odometer());
        return vehicles.save(vehicle);
    }

    @GetMapping
    public List<Vehicle> list(@RequestParam(required = false) String registrationNumber,
                              @RequestParam(required = false) String organizationRma) {
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
        return vehicles.findById(id).orElseThrow(() -> new NotFoundException("Транспорт не найден"));
    }
}
