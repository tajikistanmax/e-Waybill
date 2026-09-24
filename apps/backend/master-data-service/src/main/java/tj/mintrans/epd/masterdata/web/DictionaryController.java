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
import org.springframework.data.repository.CrudRepository;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tj.mintrans.epd.masterdata.config.CurrentUser;
import tj.mintrans.epd.masterdata.domain.Cargo;
import tj.mintrans.epd.masterdata.domain.Client;
import tj.mintrans.epd.masterdata.domain.Coefficient;
import tj.mintrans.epd.masterdata.domain.FuelNorm;
import tj.mintrans.epd.masterdata.domain.Route;
import tj.mintrans.epd.masterdata.domain.Tariff;
import tj.mintrans.epd.masterdata.repository.CargoRepository;
import tj.mintrans.epd.masterdata.repository.ClientRepository;
import tj.mintrans.epd.masterdata.repository.CoefficientRepository;
import tj.mintrans.epd.masterdata.repository.FuelNormRepository;
import tj.mintrans.epd.masterdata.repository.RouteRepository;
import tj.mintrans.epd.masterdata.repository.TariffRepository;
import tj.mintrans.epd.masterdata.service.AuditService;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
    private final CargoRepository cargos;
    private final CurrentUser currentUser;
    private final AuditService audit;

    public DictionaryController(RouteRepository routes, ClientRepository clients,
                                FuelNormRepository fuelNorms, CoefficientRepository coefficients,
                                TariffRepository tariffs, CargoRepository cargos,
                                CurrentUser currentUser, AuditService audit) {
        this.routes = routes;
        this.clients = clients;
        this.fuelNorms = fuelNorms;
        this.coefficients = coefficients;
        this.tariffs = tariffs;
        this.cargos = cargos;
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
            // id задан — правится ИМЕННО эта запись (в т.ч. с изменением номера); пусто — апсерт по
            // номеру в организации, как раньше (редактирование из UI, MIGRATION.md 8.11).
            UUID id,
            @NotBlank @Size(max = 10) String number,
            @NotBlank String name,
            Short transportType,
            Short regionId,
            // Тип маршрута — «мягкий» код справочника RouteType (V63); nullable.
            Short routeTypeCode,
            String organizationRma,
            // Коэффициентные и путевые поля (V28, перенос routes из ИС «Роҳхат»).
            // mountainCoefValue / inCityCoefValue — ЗНАЧЕНИЯ коэффициентов, не ключи справочника.
            Long winterCoefId,
            Short mountainCoefValue,
            Short inCityCoefValue,
            Short stationCoef,
            Short roadQuality,
            Boolean excludingCoef,
            Double additionalFuel100,
            Double additionalFuel,
            Double condFuel,
            Double heatingFuel,
            Double distanceA,
            Double distanceB,
            Double beginPathA,
            Double beginPathB,
            Short plannedLap,
            Double coeUseCapacity,
            Double averageLengthPassSeat,
            // Поля формы legacy без расчёта/печати (V67, MIGRATION.md 2.28): пункты А/Б, время одного
            // рейса А/Б, срок свидетельства, город/район, координаты (широта −90…90, долгота −180…180).
            @Size(max = 100) String nameA,
            @Size(max = 100) String nameB,
            java.time.LocalTime timeOneLapA,
            java.time.LocalTime timeOneLapB,
            java.time.LocalDate validCert,
            @Size(max = 200) String cityName,
            // План выручки по дням недели (legacy week_days_earnings) — справочный JSON, V72.
            @Size(max = 2000) String weekDaysEarnings,
            @DecimalMin(value = "-90", message = "Широта: −90…90") @jakarta.validation.constraints.DecimalMax(value = "90", message = "Широта: −90…90") BigDecimal latitude,
            @DecimalMin(value = "-180", message = "Долгота: −180…180") @jakarta.validation.constraints.DecimalMax(value = "180", message = "Долгота: −180…180") BigDecimal longitude) {
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
        var existing = req.id() != null
                ? Optional.of(byId(routes, req.id(), "Маршрут"))
                : routes.findByOrganizationRmaAndNumber(org, req.number());
        existing.ifPresent(r -> assertOwnOrg(r.getOrganizationRma()));
        String oldValue = existing.map(Route::getName).orElse(null); // до мутации (existing и route — один объект)
        var route = existing.orElseGet(Route::new);
        route.setOrganizationRma(org);
        route.setNumber(req.number());
        route.setName(req.name());
        route.setTransportType(req.transportType());
        route.setRegionId(req.regionId());
        route.setRouteTypeCode(req.routeTypeCode());
        route.setWinterCoefId(req.winterCoefId());
        route.setMountainCoefValue(req.mountainCoefValue());
        route.setInCityCoefValue(req.inCityCoefValue());
        route.setStationCoef(req.stationCoef());
        route.setRoadQuality(req.roadQuality());
        route.setExcludingCoef(Boolean.TRUE.equals(req.excludingCoef()));
        route.setAdditionalFuel100(req.additionalFuel100());
        route.setAdditionalFuel(req.additionalFuel());
        route.setCondFuel(req.condFuel());
        route.setHeatingFuel(req.heatingFuel());
        route.setDistanceA(req.distanceA());
        route.setDistanceB(req.distanceB());
        route.setBeginPathA(req.beginPathA());
        route.setBeginPathB(req.beginPathB());
        route.setPlannedLap(req.plannedLap());
        route.setCoeUseCapacity(req.coeUseCapacity());
        route.setAverageLengthPassSeat(req.averageLengthPassSeat());
        // Поля формы legacy (V67, 2.28) — как есть; пустые строки → null.
        route.setNameA(trimToNull(req.nameA()));
        route.setNameB(trimToNull(req.nameB()));
        route.setTimeOneLapA(req.timeOneLapA());
        route.setTimeOneLapB(req.timeOneLapB());
        route.setValidCert(req.validCert());
        route.setCityName(trimToNull(req.cityName()));
        route.setWeekDaysEarnings(trimToNull(req.weekDaysEarnings()));
        route.setLatitude(req.latitude());
        route.setLongitude(req.longitude());
        var savedRoute = routes.save(route);
        audit.record(existing.isPresent() ? AuditService.UPDATE : AuditService.CREATE,
                "ROUTE", org + "/" + req.number(), oldValue, req.name());
        return saved(existing, savedRoute);
    }

    // ------------------------------------------------------------------ клиенты

    public record ClientRequest(
            UUID id,
            @NotBlank @Size(max = 10) String number,
            @NotBlank String name,
            // Адрес обязателен, как в legacy ClientRequest (MIGRATION.md 12.15): он печатается в
            // накладной. Телефон — нет (с 24.09.2026): при обязательном поле в старой платформе у
            // 29 % клиентов вписано «1» (анализ базы, раздел 9.3 п. 2).
            @NotBlank(message = "Укажите адрес клиента") String address,
            String phone,
            String organizationRma,
            // Вид клиента (legacy clients.type): 1 заказчик, 2 грузополучатель, 3 грузоотправитель,
            // 4 экспедитор; не передан → 1. Банковские реквизиты — свободный текст (MIGRATION.md 2.24).
            @Min(value = 1, message = "Вид клиента: 1–4") @Max(value = 4, message = "Вид клиента: 1–4") Short type,
            @Size(max = 50) String riam,
            @Size(max = 20) String rma,
            @Size(max = 50) String account,
            @Size(max = 50) String correspondenceAccount,
            @Size(max = 20) String mfo,
            @Size(max = 200) String bankName) {
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
        var existing = req.id() != null
                ? Optional.of(byId(clients, req.id(), "Клиент"))
                : clients.findByOrganizationRmaAndNumber(org, req.number());
        existing.ifPresent(c -> assertOwnOrg(c.getOrganizationRma()));
        String oldValue = existing.map(Client::getName).orElse(null); // до мутации
        var client = existing.orElseGet(Client::new);
        client.setOrganizationRma(org);
        client.setNumber(req.number());
        client.setName(req.name());
        client.setAddress(req.address());
        client.setPhone(req.phone());
        // Вид и реквизиты (2.24): вид не передан → 1 (заказчик), как default legacy-формы.
        client.setType(req.type() == null ? (short) 1 : req.type());
        client.setRiam(trimToNull(req.riam()));
        client.setRma(trimToNull(req.rma()));
        client.setAccount(trimToNull(req.account()));
        client.setCorrespondenceAccount(trimToNull(req.correspondenceAccount()));
        client.setMfo(trimToNull(req.mfo()));
        client.setBankName(trimToNull(req.bankName()));
        var savedClient = clients.save(client);
        audit.record(existing.isPresent() ? AuditService.UPDATE : AuditService.CREATE,
                "CLIENT", org + "/" + req.number(), oldValue, req.name());
        return saved(existing, savedClient);
    }

    // ------------------------------------------------------------------ нормы расхода

    public record FuelNormRequest(
            UUID id,
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
        var existing = req.id() != null ? Optional.of(byId(fuelNorms, req.id(), "Норма расхода"))
                : brand == null
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
            UUID id,
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
        var existing = req.id() != null ? Optional.of(byId(coefficients, req.id(), "Коэффициент"))
                : coefficients.findByKindAndName(req.kind(), req.name());
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
            UUID id,
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
        var existing = req.id() != null ? Optional.of(byId(tariffs, req.id(), "Тариф"))
                : req.fuelType() == null
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

    // ------------------------------------------------------------------------- грузы

    /**
     * Груз (бор) — перенос {@code cargos} из ИС «Роҳхат» (2022_06_24_204806_create_cargo_table.php).
     * Платформенный справочник (НЕ организация-скоуп): в эталоне cargos не имеет колонки
     * компании — один общий список грузов для всех перевозчиков, правят COMPANY_ADMIN/SYSTEM_ADMIN
     * (как Client), апсерт по названию (регистронезависимо).
     */
    public record CargoRequest(
            UUID id,
            @NotBlank String name,
            // Тип и единица обязательны, как в legacy CargoRequest (MIGRATION.md 12.15). Цена — нет
            // (с 24.09.2026): в расчётах не участвует, а при обязательном поле в старой платформе
            // у 53 % грузов вписано «1» (анализ базы, раздел 9.3 п. 2).
            @NotBlank(message = "Укажите тип груза") String type,
            @NotBlank(message = "Укажите единицу измерения груза") String unit,
            BigDecimal price,
            Short cargoClass) {
    }

    @GetMapping("/cargos")
    public List<Cargo> listCargos() {
        return cargos.findAll();
    }

    @PostMapping("/cargos")
    @PreAuthorize("hasAnyRole('COMPANY_ADMIN','SYSTEM_ADMIN')")
    public ResponseEntity<Cargo> upsertCargo(@Valid @RequestBody CargoRequest req) {
        var existing = req.id() != null ? Optional.of(byId(cargos, req.id(), "Груз"))
                : cargos.findFirstByNameIgnoreCase(req.name());
        String oldValue = existing.map(c -> String.valueOf(c.getPrice())).orElse(null); // до мутации
        var cargo = existing.orElseGet(Cargo::new);
        cargo.setName(req.name());
        cargo.setType(req.type());
        cargo.setUnit(req.unit());
        cargo.setPrice(req.price());
        cargo.setCargoClass(req.cargoClass());
        var savedCargo = cargos.save(cargo);
        audit.record(existing.isPresent() ? AuditService.UPDATE : AuditService.CREATE,
                "CARGO", req.name(), oldValue, String.valueOf(req.price()));
        return saved(existing, savedCargo);
    }

    // ------------------------------------------------------------------ удаление записей

    /**
     * Удаление записи справочника (MIGRATION.md 8.11 — в legacy у каждого справочника были кнопки
     * правки и удаления). Организационные справочники (маршруты, клиенты) тенант удаляет только свои;
     * национальные (нормы, коэффициенты, тарифы) — системный администратор.
     * Запись, на которую ссылаются путевые листы, физически остаётся в их снимках — удаление
     * справочника не меняет уже выписанные документы.
     */
    @DeleteMapping("/routes/{id}")
    @PreAuthorize("hasAnyRole('COMPANY_ADMIN','SYSTEM_ADMIN')")
    public ResponseEntity<Void> deleteRoute(@PathVariable UUID id) {
        var route = byId(routes, id, "Маршрут");
        assertOwnOrg(route.getOrganizationRma());
        routes.delete(route);
        audit.record(AuditService.DELETE, "ROUTE", route.getOrganizationRma() + "/" + route.getNumber(), route.getName(), null);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/clients/{id}")
    @PreAuthorize("hasAnyRole('COMPANY_ADMIN','SYSTEM_ADMIN')")
    public ResponseEntity<Void> deleteClient(@PathVariable UUID id) {
        var client = byId(clients, id, "Клиент");
        assertOwnOrg(client.getOrganizationRma());
        clients.delete(client);
        audit.record(AuditService.DELETE, "CLIENT", client.getOrganizationRma() + "/" + client.getNumber(), client.getName(), null);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/cargos/{id}")
    @PreAuthorize("hasAnyRole('COMPANY_ADMIN','SYSTEM_ADMIN')")
    public ResponseEntity<Void> deleteCargo(@PathVariable UUID id) {
        var cargo = byId(cargos, id, "Груз");
        cargos.delete(cargo);
        audit.record(AuditService.DELETE, "CARGO", cargo.getName(), String.valueOf(cargo.getPrice()), null);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/fuel-norms/{id}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<Void> deleteFuelNorm(@PathVariable UUID id) {
        var norm = byId(fuelNorms, id, "Норма расхода");
        fuelNorms.delete(norm);
        audit.record(AuditService.DELETE, "FUEL_NORM",
                norm.getTransportType() + "/" + (norm.getBrand() == null ? "*" : norm.getBrand()),
                String.valueOf(norm.getBaseNorm()), null);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/coefficients/{id}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<Void> deleteCoefficient(@PathVariable UUID id) {
        var coefficient = byId(coefficients, id, "Коэффициент");
        coefficients.delete(coefficient);
        audit.record(AuditService.DELETE, "COEFFICIENT", coefficient.getKind() + "/" + coefficient.getName(),
                String.valueOf(coefficient.getValue()), null);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/tariffs/{id}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<Void> deleteTariff(@PathVariable UUID id) {
        var tariff = byId(tariffs, id, "Тариф");
        tariffs.delete(tariff);
        audit.record(AuditService.DELETE, "TARIFF",
                tariff.getTransportType() + "/" + (tariff.getFuelType() == null ? "*" : tariff.getFuelType()),
                String.valueOf(tariff.getPricePerKm()), null);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------ вспомогательное

    /** Запись справочника по идентификатору; нет такой — 404 с понятным текстом. */
    private static <T, ID> T byId(CrudRepository<T, ID> repo, ID id, String label) {
        return repo.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, label + " не найден(а): " + id));
    }

    /** Тенант работает только с записями своей организации (правка/удаление по id). */
    private void assertOwnOrg(String recordOrg) {
        if (!currentUser.isTenantScoped()) {
            return;
        }
        String own = currentUser.organizationRma().orElse(null);
        if (own == null || !own.equals(recordOrg)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Запись справочника не найдена");
        }
    }

    /** Пустая/пробельная строка → {@code null}, иначе обрезанная (реквизиты клиента, 2.24). */
    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static <T> ResponseEntity<T> saved(Optional<?> existing, T body) {
        return ResponseEntity.status(existing.isPresent() ? HttpStatus.OK : HttpStatus.CREATED).body(body);
    }
}
