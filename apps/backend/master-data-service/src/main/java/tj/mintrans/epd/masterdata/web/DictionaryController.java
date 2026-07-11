package tj.mintrans.epd.masterdata.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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
import tj.mintrans.epd.masterdata.service.AuditService;

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
    private final AuditService audit;

    public DictionaryController(RouteRepository routes, ClientRepository clients,
                                FuelNormRepository fuelNorms, CoefficientRepository coefficients,
                                TariffRepository tariffs, CurrentUser currentUser, AuditService audit) {
        this.routes = routes;
        this.clients = clients;
        this.fuelNorms = fuelNorms;
        this.coefficients = coefficients;
        this.tariffs = tariffs;
        this.currentUser = currentUser;
        this.audit = audit;
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
        String oldValue = existing.map(Route::getName).orElse(null); // до мутации (existing и route — один объект)
        var route = existing.orElseGet(Route::new);
        route.setOrganizationRma(org);
        route.setNumber(req.number());
        route.setName(req.name());
        route.setTransportType(req.transportType());
        route.setRegionId(req.regionId());
        var savedRoute = routes.save(route);
        audit.record(existing.isPresent() ? AuditService.UPDATE : AuditService.CREATE,
                "ROUTE", org + "/" + req.number(), oldValue, req.name());
        return saved(existing, savedRoute);
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
        String oldValue = existing.map(Client::getName).orElse(null); // до мутации
        var client = existing.orElseGet(Client::new);
        client.setOrganizationRma(org);
        client.setNumber(req.number());
        client.setName(req.name());
        client.setAddress(req.address());
        client.setPhone(req.phone());
        var savedClient = clients.save(client);
        audit.record(existing.isPresent() ? AuditService.UPDATE : AuditService.CREATE,
                "CLIENT", org + "/" + req.number(), oldValue, req.name());
        return saved(existing, savedClient);
    }

    // ------------------------------------------------------------------ нормы расхода

    public record FuelNormRequest(
            @NotNull Short transportType,
            String brand,
            @NotNull @DecimalMin(value = "0.0", inclusive = false,
                    message = "Норма расхода должна быть положительной") BigDecimal baseNorm) {
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
        String oldValue = existing.map(n -> String.valueOf(n.getBaseNorm())).orElse(null); // до мутации
        var norm = existing.orElseGet(FuelNorm::new);
        norm.setTransportType(req.transportType());
        norm.setBrand(brand);
        norm.setBaseNorm(req.baseNorm());
        var savedNorm = fuelNorms.save(norm);
        audit.record(existing.isPresent() ? AuditService.UPDATE : AuditService.CREATE,
                "FUEL_NORM", req.transportType() + "/" + (brand == null ? "*" : brand),
                oldValue, String.valueOf(req.baseNorm()));
        return saved(existing, savedNorm);
    }

    // ------------------------------------------------------------------ коэффициенты

    public record CoefficientRequest(
            @NotBlank @Pattern(regexp = "WINTER|CITY|HIGHLAND|USAGE",
                    message = "Вид коэффициента: WINTER | CITY | HIGHLAND | USAGE") String kind,
            @NotBlank String name,
            @NotNull @DecimalMin(value = "0.0", inclusive = false,
                    message = "Коэффициент должен быть положительным") BigDecimal value,
            Short regionId,
            @Min(1) @Max(12) Short monthFrom,
            @Min(1) @Max(12) Short monthTo) {
    }

    @GetMapping("/coefficients")
    public List<Coefficient> listCoefficients() {
        return coefficients.findAll();
    }

    @PostMapping("/coefficients")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<Coefficient> upsertCoefficient(@Valid @RequestBody CoefficientRequest req) {
        var existing = coefficients.findByKindAndName(req.kind(), req.name());
        String oldValue = existing.map(c -> String.valueOf(c.getValue())).orElse(null); // до мутации
        var coefficient = existing.orElseGet(Coefficient::new);
        coefficient.setKind(req.kind());
        coefficient.setName(req.name());
        coefficient.setValue(req.value());
        coefficient.setRegionId(req.regionId());
        coefficient.setMonthFrom(req.monthFrom());
        coefficient.setMonthTo(req.monthTo());
        var savedCoefficient = coefficients.save(coefficient);
        audit.record(existing.isPresent() ? AuditService.UPDATE : AuditService.CREATE,
                "COEFFICIENT", req.kind() + "/" + req.name(), oldValue, String.valueOf(req.value()));
        return saved(existing, savedCoefficient);
    }

    // ------------------------------------------------------------------ тарифы (нархнома)

    public record TariffRequest(
            @NotNull Short transportType,
            Short fuelType,
            @NotNull @DecimalMin(value = "0.0", inclusive = false,
                    message = "Тариф за км должен быть положительным") BigDecimal pricePerKm) {
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
        String oldValue = existing.map(t -> String.valueOf(t.getPricePerKm())).orElse(null); // до мутации
        var tariff = existing.orElseGet(Tariff::new);
        tariff.setTransportType(req.transportType());
        tariff.setFuelType(req.fuelType());
        tariff.setPricePerKm(req.pricePerKm());
        var savedTariff = tariffs.save(tariff);
        audit.record(existing.isPresent() ? AuditService.UPDATE : AuditService.CREATE,
                "TARIFF", req.transportType() + "/" + (req.fuelType() == null ? "*" : req.fuelType()),
                oldValue, String.valueOf(req.pricePerKm()));
        return saved(existing, savedTariff);
    }

    // ------------------------------------------------------------------ вспомогательное

    private static <T> ResponseEntity<T> saved(Optional<?> existing, T body) {
        return ResponseEntity.status(existing.isPresent() ? HttpStatus.OK : HttpStatus.CREATED).body(body);
    }
}
