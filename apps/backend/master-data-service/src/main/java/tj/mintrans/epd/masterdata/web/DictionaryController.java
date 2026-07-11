package tj.mintrans.epd.masterdata.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tj.mintrans.epd.masterdata.config.CurrentUser;
import tj.mintrans.epd.masterdata.domain.Client;
import tj.mintrans.epd.masterdata.domain.Coefficient;
import tj.mintrans.epd.masterdata.domain.FuelNorm;
import tj.mintrans.epd.masterdata.domain.Route;
import tj.mintrans.epd.masterdata.domain.Tariff;
import tj.mintrans.epd.masterdata.repository.ClientRepository;
import tj.mintrans.epd.masterdata.repository.CoefficientRepository;
import tj.mintrans.epd.masterdata.repository.FuelNormRepository;
import tj.mintrans.epd.masterdata.repository.RouteRepository;
import tj.mintrans.epd.masterdata.repository.TariffRepository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Справочники нормирования и тарификации (spec/notes/02, раздел 3).
 * GET — чтение (permitAll в SecurityConfig), POST — upsert по уникальному ключу.
 * Маршруты/клиенты правят администраторы организаций; нормы/коэффициенты/тарифы —
 * только системный администратор (единые для платформы).
 */
@RestController
@RequestMapping("/api/v1/dictionaries")
public class DictionaryController {

    private final RouteRepository routes;
    private final ClientRepository clients;
    private final FuelNormRepository fuelNorms;
    private final CoefficientRepository coefficients;
    private final TariffRepository tariffs;
    private final CurrentUser currentUser;

    public DictionaryController(RouteRepository routes, ClientRepository clients,
                                FuelNormRepository fuelNorms, CoefficientRepository coefficients,
                                TariffRepository tariffs, CurrentUser currentUser) {
        this.routes = routes;
        this.clients = clients;
        this.fuelNorms = fuelNorms;
        this.coefficients = coefficients;
        this.tariffs = tariffs;
        this.currentUser = currentUser;
    }

    /**
     * Организация, к которой привязывается запись справочника при upsert.
     * COMPANY_ADMIN (tenant-scoped) — только своя организация (параметр запроса игнорируется).
     * SYSTEM_ADMIN — обязан указать организацию явно (organizationRma в теле).
     */
    private String resolveWriteOrg(String requestedOrg) {
        if (currentUser.isTenantScoped()) {
            return currentUser.organizationRma().orElseThrow(() -> new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Организация не определена в токене"));
        }
        if (requestedOrg == null || requestedOrg.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "SYSTEM_ADMIN должен указать organizationRma владельца записи");
        }
        return requestedOrg.trim();
    }

    // ------------------------------------------------------------------ маршруты

    public record RouteRequest(
            @NotBlank @Size(max = 10) String number,
            @NotBlank String name,
            Short transportType,
            Short regionId,
            String organizationRma) {
    }

    @GetMapping("/routes")
    public List<Route> listRoutes() {
        // Тенант видит маршруты только своей организации; платформа и внутренние вызовы — все.
        if (currentUser.isTenantScoped()) {
            return routes.findByOrganizationRma(currentUser.organizationRma().orElse(null));
        }
        return routes.findAll();
    }

    @PostMapping("/routes")
    @PreAuthorize("hasAnyRole('COMPANY_ADMIN','SYSTEM_ADMIN')")
    public ResponseEntity<Route> upsertRoute(@Valid @RequestBody RouteRequest req) {
        String org = resolveWriteOrg(req.organizationRma());
        var existing = routes.findByOrganizationRmaAndNumber(org, req.number());
        var route = existing.orElseGet(Route::new);
        route.setOrganizationRma(org);
        route.setNumber(req.number());
        route.setName(req.name());
        route.setTransportType(req.transportType());
        route.setRegionId(req.regionId());
        return saved(existing, routes.save(route));
    }

    // ------------------------------------------------------------------ клиенты

    public record ClientRequest(
            @NotBlank @Size(max = 10) String number,
            @NotBlank String name,
            String address,
            String phone,
            String organizationRma) {
    }

    @GetMapping("/clients")
    public List<Client> listClients() {
        // Тенант видит клиентов только своей организации; платформа и внутренние вызовы — все.
        if (currentUser.isTenantScoped()) {
            return clients.findByOrganizationRma(currentUser.organizationRma().orElse(null));
        }
        return clients.findAll();
    }

    @PostMapping("/clients")
    @PreAuthorize("hasAnyRole('COMPANY_ADMIN','SYSTEM_ADMIN')")
    public ResponseEntity<Client> upsertClient(@Valid @RequestBody ClientRequest req) {
        String org = resolveWriteOrg(req.organizationRma());
        var existing = clients.findByOrganizationRmaAndNumber(org, req.number());
        var client = existing.orElseGet(Client::new);
        client.setOrganizationRma(org);
        client.setNumber(req.number());
        client.setName(req.name());
        client.setAddress(req.address());
        client.setPhone(req.phone());
        return saved(existing, clients.save(client));
    }

    // ------------------------------------------------------------------ нормы расхода

    public record FuelNormRequest(
            @NotNull Short transportType,
            String brand,
            @NotNull BigDecimal baseNorm) {
    }

    @GetMapping("/fuel-norms")
    public List<FuelNorm> listFuelNorms() {
        return fuelNorms.findAll();
    }

    @PostMapping("/fuel-norms")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<FuelNorm> upsertFuelNorm(@Valid @RequestBody FuelNormRequest req) {
        String brand = req.brand() == null || req.brand().isBlank() ? null : req.brand();
        var existing = brand == null
                ? fuelNorms.findByTransportTypeAndBrandIsNull(req.transportType())
                : fuelNorms.findByTransportTypeAndBrand(req.transportType(), brand);
        var norm = existing.orElseGet(FuelNorm::new);
        norm.setTransportType(req.transportType());
        norm.setBrand(brand);
        norm.setBaseNorm(req.baseNorm());
        return saved(existing, fuelNorms.save(norm));
    }

    // ------------------------------------------------------------------ коэффициенты

    public record CoefficientRequest(
            @NotBlank String kind,
            @NotBlank String name,
            @NotNull BigDecimal value,
            Short regionId,
            Short monthFrom,
            Short monthTo) {
    }

    @GetMapping("/coefficients")
    public List<Coefficient> listCoefficients() {
        return coefficients.findAll();
    }

    @PostMapping("/coefficients")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<Coefficient> upsertCoefficient(@Valid @RequestBody CoefficientRequest req) {
        var existing = coefficients.findByKindAndName(req.kind(), req.name());
        var coefficient = existing.orElseGet(Coefficient::new);
        coefficient.setKind(req.kind());
        coefficient.setName(req.name());
        coefficient.setValue(req.value());
        coefficient.setRegionId(req.regionId());
        coefficient.setMonthFrom(req.monthFrom());
        coefficient.setMonthTo(req.monthTo());
        return saved(existing, coefficients.save(coefficient));
    }

    // ------------------------------------------------------------------ тарифы (нархнома)

    public record TariffRequest(
            @NotNull Short transportType,
            Short fuelType,
            @NotNull BigDecimal pricePerKm) {
    }

    @GetMapping("/tariffs")
    public List<Tariff> listTariffs() {
        return tariffs.findAll();
    }

    @PostMapping("/tariffs")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<Tariff> upsertTariff(@Valid @RequestBody TariffRequest req) {
        var existing = req.fuelType() == null
                ? tariffs.findByTransportTypeAndFuelTypeIsNull(req.transportType())
                : tariffs.findByTransportTypeAndFuelType(req.transportType(), req.fuelType());
        var tariff = existing.orElseGet(Tariff::new);
        tariff.setTransportType(req.transportType());
        tariff.setFuelType(req.fuelType());
        tariff.setPricePerKm(req.pricePerKm());
        return saved(existing, tariffs.save(tariff));
    }

    // ------------------------------------------------------------------ вспомогательное

    private static <T> ResponseEntity<T> saved(Optional<?> existing, T body) {
        return ResponseEntity.status(existing.isPresent() ? HttpStatus.OK : HttpStatus.CREATED).body(body);
    }
}
