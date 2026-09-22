package tj.mintrans.epd.masterdata.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.data.repository.CrudRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tj.mintrans.epd.masterdata.domain.Brand;
import tj.mintrans.epd.masterdata.domain.CityCoef;
import tj.mintrans.epd.masterdata.domain.Direction;
import tj.mintrans.epd.masterdata.domain.DriveClass;
import tj.mintrans.epd.masterdata.domain.FuelWinterCoef;
import tj.mintrans.epd.masterdata.domain.MountainCoef;
import tj.mintrans.epd.masterdata.domain.RouteTariff;
import tj.mintrans.epd.masterdata.domain.UsedCoef;
import tj.mintrans.epd.masterdata.repository.BrandRepository;
import tj.mintrans.epd.masterdata.repository.CityCoefRepository;
import tj.mintrans.epd.masterdata.repository.DirectionRepository;
import tj.mintrans.epd.masterdata.repository.DriveClassRepository;
import tj.mintrans.epd.masterdata.repository.FuelWinterCoefRepository;
import tj.mintrans.epd.masterdata.repository.MountainCoefRepository;
import tj.mintrans.epd.masterdata.repository.RouteTariffRepository;
import tj.mintrans.epd.masterdata.repository.UsedCoefRepository;
import tj.mintrans.epd.masterdata.service.AuditService;
import tj.mintrans.epd.masterdata.web.error.NotFoundException;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Справочники расчётного ядра (перенос схемы ИС «Роҳхат»): марки ТС с нормативами расхода
 * топлива и коэффициенты (зимний/горный/городской/износ, классы водителей, направления,
 * тарифы маршрута). Потребитель чтения — движок расчёта в waybill-service (пакет calc,
 * {@code CoefficientCalculator}, {@code FuelNormCalculator}).
 *
 * <p>GET — любой аутентифицированный (SecurityConfig: {@code /api/v1/**} authenticated).
 * POST — upsert по естественному ключу каждой сущности, только {@code SYSTEM_ADMIN}: эти
 * справочники единые для платформы (не организация-скоуп), полноценный АРМ нормировщика
 * появится позже — сейчас закрывается сам факт отсутствия write-пути (правки раньше требовали
 * ручной SQL-миграции).</p>
 */
@RestController
@RequestMapping("/api/v1/legacy-ref")
public class LegacyReferenceController {

    private final BrandRepository brands;
    private final FuelWinterCoefRepository winterCoefs;
    private final MountainCoefRepository mountainCoefs;
    private final CityCoefRepository cityCoefs;
    private final UsedCoefRepository usedCoefs;
    private final DriveClassRepository driveClasses;
    private final DirectionRepository directions;
    private final RouteTariffRepository routeTariffs;
    private final AuditService audit;

    public LegacyReferenceController(BrandRepository brands, FuelWinterCoefRepository winterCoefs,
                                     MountainCoefRepository mountainCoefs, CityCoefRepository cityCoefs,
                                     UsedCoefRepository usedCoefs, DriveClassRepository driveClasses,
                                     DirectionRepository directions, RouteTariffRepository routeTariffs,
                                     AuditService audit) {
        this.brands = brands;
        this.winterCoefs = winterCoefs;
        this.mountainCoefs = mountainCoefs;
        this.cityCoefs = cityCoefs;
        this.usedCoefs = usedCoefs;
        this.driveClasses = driveClasses;
        this.directions = directions;
        this.routeTariffs = routeTariffs;
        this.audit = audit;
    }

    // -------------------------------------------------------------------- марки ТС

    @GetMapping("/brands")
    public List<Brand> listBrands() {
        return brands.findAll();
    }

    /** Марка по id (для расчёта: снимок марки ТС берётся отсюда). */
    @GetMapping("/brands/{id}")
    public Brand brand(@PathVariable long id) {
        return brands.findById(id).orElseThrow(() -> new NotFoundException("Марка id=" + id + " не найдена"));
    }

    /** Марка по названию (регистронезависимо) — снимок ТС хранит имя марки, не id. */
    @GetMapping(value = "/brands", params = "name")
    public Brand brandByName(@RequestParam String name) {
        return brands.findFirstByNameIgnoreCase(name)
                .orElseThrow(() -> new NotFoundException("Марка «" + name + "» не найдена"));
    }

    public record BrandRequest(
            // id задан — правится именно эта запись (в т.ч. с переименованием); пусто — апсерт по ключу.
            Long id,
            @NotBlank String name,
            String number,
            String model,
            Long typeId,
            Integer capacity,
            Double carrying,
            Double costServices,
            String fuel100,
            String fuel100Dushanbe,
            String fuelHour,
            Double fuelInteriorHeating,
            Double tariffRate) {
    }

    /** Upsert по естественному ключу марки (имя + модель) — после V57 имя не уникально
     *  (у одной марки много моделей), поэтому различаем по паре, иначе нельзя завести вторую модель. */
    @PostMapping("/brands")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<Brand> upsertBrand(@Valid @RequestBody BrandRequest req) {
        var existing = req.id() != null ? Optional.of(byId(brands, req.id(), "Марка"))
                : brands.findFirstByNameIgnoreCaseAndModelIgnoreCase(req.name(), req.model() == null ? "" : req.model());
        String oldValue = existing.map(Brand::getModel).orElse(null); // до мутации
        var brand = existing.orElseGet(Brand::new);
        brand.setName(req.name());
        brand.setNumber(req.number());
        brand.setModel(req.model() == null ? "" : req.model());
        brand.setTypeId(req.typeId() == null ? 0L : req.typeId());
        brand.setCapacity(req.capacity());
        brand.setCarrying(req.carrying());
        brand.setCostServices(req.costServices());
        brand.setFuel100(req.fuel100());
        brand.setFuel100Dushanbe(req.fuel100Dushanbe());
        brand.setFuelHour(req.fuelHour());
        brand.setFuelInteriorHeating(req.fuelInteriorHeating());
        brand.setTariffRate(req.tariffRate());
        var saved = brands.save(brand);
        audit.record(existing.isPresent() ? AuditService.UPDATE : AuditService.CREATE,
                "BRAND", req.name(), oldValue, req.model());
        return saved(existing, saved);
    }

    // ------------------------------------------------------------- зимний коэффициент

    @GetMapping("/winter-coefs")
    public List<FuelWinterCoef> winterCoefs() {
        return winterCoefs.findAll();
    }

    public record FuelWinterCoefRequest(
            Long id,
            @NotBlank String name,
            LocalDate periodFrom,
            LocalDate periodTo,
            @NotNull Short coef) {
    }

    @PostMapping("/winter-coefs")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<FuelWinterCoef> upsertWinterCoef(@Valid @RequestBody FuelWinterCoefRequest req) {
        var existing = req.id() != null ? Optional.of(byId(winterCoefs, req.id(), "Зимний коэффициент"))
                : winterCoefs.findFirstByNameIgnoreCase(req.name());
        String oldValue = existing.map(c -> String.valueOf(c.getCoef())).orElse(null); // до мутации
        var coef = existing.orElseGet(FuelWinterCoef::new);
        coef.setName(req.name());
        coef.setPeriodFrom(req.periodFrom());
        coef.setPeriodTo(req.periodTo());
        coef.setCoef(req.coef());
        var savedCoef = winterCoefs.save(coef);
        audit.record(existing.isPresent() ? AuditService.UPDATE : AuditService.CREATE,
                "FUEL_WINTER_COEF", req.name(), oldValue, String.valueOf(req.coef()));
        return saved(existing, savedCoef);
    }

    // ------------------------------------------------------------- горный коэффициент

    @GetMapping("/mountain-coefs")
    public List<MountainCoef> mountainCoefs() {
        return mountainCoefs.findAll();
    }

    public record MountainCoefRequest(Long id, @NotBlank String name, @NotNull Short coef) {
    }

    @PostMapping("/mountain-coefs")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<MountainCoef> upsertMountainCoef(@Valid @RequestBody MountainCoefRequest req) {
        var existing = req.id() != null ? Optional.of(byId(mountainCoefs, req.id(), "Высокогорный коэффициент"))
                : mountainCoefs.findFirstByNameIgnoreCase(req.name());
        String oldValue = existing.map(c -> String.valueOf(c.getCoef())).orElse(null); // до мутации
        var coef = existing.orElseGet(MountainCoef::new);
        coef.setName(req.name());
        coef.setCoef(req.coef());
        var savedCoef = mountainCoefs.save(coef);
        audit.record(existing.isPresent() ? AuditService.UPDATE : AuditService.CREATE,
                "MOUNTAIN_COEF", req.name(), oldValue, String.valueOf(req.coef()));
        return saved(existing, savedCoef);
    }

    // ----------------------------------------------------------- городской коэффициент

    @GetMapping("/city-coefs")
    public List<CityCoef> cityCoefs() {
        return cityCoefs.findAll();
    }

    public record CityCoefRequest(Long id, @NotBlank String name, @NotNull Short coef) {
    }

    @PostMapping("/city-coefs")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<CityCoef> upsertCityCoef(@Valid @RequestBody CityCoefRequest req) {
        var existing = req.id() != null ? Optional.of(byId(cityCoefs, req.id(), "Внутригородской коэффициент"))
                : cityCoefs.findFirstByNameIgnoreCase(req.name());
        String oldValue = existing.map(c -> String.valueOf(c.getCoef())).orElse(null); // до мутации
        var coef = existing.orElseGet(CityCoef::new);
        coef.setName(req.name());
        coef.setCoef(req.coef());
        var savedCoef = cityCoefs.save(coef);
        audit.record(existing.isPresent() ? AuditService.UPDATE : AuditService.CREATE,
                "CITY_COEF", req.name(), oldValue, String.valueOf(req.coef()));
        return saved(existing, savedCoef);
    }

    // ------------------------------------------------------------- коэффициент износа

    @GetMapping("/used-coefs")
    public List<UsedCoef> usedCoefs() {
        return usedCoefs.findAll();
    }

    public record UsedCoefRequest(Long id, @NotNull Short year, @NotNull Integer km, @NotNull Short coef) {
    }

    /** Upsert по паре (year, km) — порог возраста/пробега, при превышении которых начисляется coef. */
    @PostMapping("/used-coefs")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<UsedCoef> upsertUsedCoef(@Valid @RequestBody UsedCoefRequest req) {
        var existing = req.id() != null ? Optional.of(byId(usedCoefs, req.id(), "Коэффициент износа"))
                : usedCoefs.findFirstByYearAndKm(req.year(), req.km());
        String oldValue = existing.map(c -> String.valueOf(c.getCoef())).orElse(null); // до мутации
        var coef = existing.orElseGet(UsedCoef::new);
        coef.setYear(req.year());
        coef.setKm(req.km());
        coef.setCoef(req.coef());
        var savedCoef = usedCoefs.save(coef);
        audit.record(existing.isPresent() ? AuditService.UPDATE : AuditService.CREATE,
                "USED_COEF", req.year() + "/" + req.km(), oldValue, String.valueOf(req.coef()));
        return saved(existing, savedCoef);
    }

    // ------------------------------------------------------------------ класс водителя

    @GetMapping("/drive-classes")
    public List<DriveClass> driveClasses() {
        return driveClasses.findAll();
    }

    public record DriveClassRequest(Long id, @NotBlank String driveClass, @NotNull Short coef) {
    }

    @PostMapping("/drive-classes")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<DriveClass> upsertDriveClass(@Valid @RequestBody DriveClassRequest req) {
        var existing = req.id() != null ? Optional.of(byId(driveClasses, req.id(), "Класс водителя"))
                : driveClasses.findFirstByDriveClassIgnoreCase(req.driveClass());
        String oldValue = existing.map(c -> String.valueOf(c.getCoef())).orElse(null); // до мутации
        var driveClass = existing.orElseGet(DriveClass::new);
        driveClass.setDriveClass(req.driveClass());
        driveClass.setCoef(req.coef());
        var savedDriveClass = driveClasses.save(driveClass);
        audit.record(existing.isPresent() ? AuditService.UPDATE : AuditService.CREATE,
                "DRIVE_CLASS", req.driveClass(), oldValue, String.valueOf(req.coef()));
        return saved(existing, savedDriveClass);
    }

    // ------------------------------------------------------- направления перевозок

    @GetMapping("/directions")
    public List<Direction> directions() {
        return directions.findAll();
    }

    @GetMapping("/directions/{id}")
    public Direction direction(@PathVariable long id) {
        return directions.findById(id)
                .orElseThrow(() -> new NotFoundException("Направление id=" + id + " не найдено"));
    }

    public record DirectionRequest(
            Long id,
            @NotBlank String title,
            Integer number,
            Long winterCoefId,
            Long mountainCoefId,
            Long inCityCoefId,
            Boolean checked) {
    }

    @PostMapping("/directions")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<Direction> upsertDirection(@Valid @RequestBody DirectionRequest req) {
        var existing = req.id() != null ? Optional.of(byId(directions, req.id(), "Направление"))
                : directions.findFirstByTitleIgnoreCase(req.title());
        String oldValue = existing.map(d -> String.valueOf(d.getNumber())).orElse(null); // до мутации
        var direction = existing.orElseGet(Direction::new);
        direction.setTitle(req.title());
        direction.setNumber(req.number());
        direction.setWinterCoefId(req.winterCoefId());
        direction.setMountainCoefId(req.mountainCoefId());
        direction.setInCityCoefId(req.inCityCoefId());
        direction.setChecked(Boolean.TRUE.equals(req.checked()));
        var savedDirection = directions.save(direction);
        audit.record(existing.isPresent() ? AuditService.UPDATE : AuditService.CREATE,
                "DIRECTION", req.title(), oldValue, String.valueOf(req.number()));
        return saved(existing, savedDirection);
    }

    // -------------------------------------------------------- тарифы маршрута (нархнома)

    /** Тарифы маршрута (нархнома): все, либо по конкретному маршруту (?routeId=). */
    @GetMapping("/route-tariffs")
    public List<RouteTariff> routeTariffs(@RequestParam(required = false) UUID routeId) {
        return routeId == null ? routeTariffs.findAll() : routeTariffs.findByRouteId(routeId);
    }

    public record RouteTariffRequest(
            Long id,
            @NotNull UUID routeId,
            Short fuelId,
            String number,
            String typeAuto,
            @NotNull Double pricePer1Mkm,
            @NotNull Double priceOneTime,
            // Коэффитсиенти иловагӣ (legacy tariffs.adv_coe) — справочное, в расчёт не входит (2.26).
            java.math.BigDecimal advCoe) {
    }

    /** Upsert по (routeId, fuelId) — fuelId=null — тариф для всех видов топлива маршрута. */
    @PostMapping("/route-tariffs")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<RouteTariff> upsertRouteTariff(@Valid @RequestBody RouteTariffRequest req) {
        var existing = req.id() != null ? Optional.of(byId(routeTariffs, req.id(), "Тариф маршрута"))
                : req.fuelId() == null
                ? routeTariffs.findFirstByRouteIdAndFuelIdIsNull(req.routeId())
                : routeTariffs.findFirstByRouteIdAndFuelId(req.routeId(), req.fuelId());
        String oldValue = existing.map(t -> String.valueOf(t.getPricePer1Mkm())).orElse(null); // до мутации
        var tariff = existing.orElseGet(RouteTariff::new);
        tariff.setRouteId(req.routeId());
        tariff.setFuelId(req.fuelId());
        tariff.setNumber(req.number());
        tariff.setTypeAuto(req.typeAuto());
        tariff.setPricePer1Mkm(req.pricePer1Mkm());
        tariff.setPriceOneTime(req.priceOneTime());
        tariff.setAdvCoe(req.advCoe());
        var savedTariff = routeTariffs.save(tariff);
        audit.record(existing.isPresent() ? AuditService.UPDATE : AuditService.CREATE,
                "ROUTE_TARIFF", req.routeId() + "/" + (req.fuelId() == null ? "*" : req.fuelId()),
                oldValue, String.valueOf(req.pricePer1Mkm()));
        return saved(existing, savedTariff);
    }

    // ------------------------------------------------------------------ удаление записей

    /**
     * Удаление записи справочника расчётного ядра (MIGRATION.md 8.11 — в legacy у справочников были
     * кнопки правки и удаления). Только системный администратор: данные едины для всей платформы.
     * На уже выписанные путевые листы удаление не влияет — там снимки значений.
     */
    @DeleteMapping("/brands/{id}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<Void> deleteBrand(@PathVariable long id) {
        var brand = byId(brands, id, "Марка");
        brands.delete(brand);
        audit.record(AuditService.DELETE, "BRAND", brand.getName(), brand.getModel(), null);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/winter-coefs/{id}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<Void> deleteWinterCoef(@PathVariable long id) {
        var coef = byId(winterCoefs, id, "Зимний коэффициент");
        winterCoefs.delete(coef);
        audit.record(AuditService.DELETE, "FUEL_WINTER_COEF", coef.getName(), String.valueOf(coef.getCoef()), null);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/mountain-coefs/{id}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<Void> deleteMountainCoef(@PathVariable long id) {
        var coef = byId(mountainCoefs, id, "Высокогорный коэффициент");
        mountainCoefs.delete(coef);
        audit.record(AuditService.DELETE, "MOUNTAIN_COEF", coef.getName(), String.valueOf(coef.getCoef()), null);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/city-coefs/{id}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<Void> deleteCityCoef(@PathVariable long id) {
        var coef = byId(cityCoefs, id, "Внутригородской коэффициент");
        cityCoefs.delete(coef);
        audit.record(AuditService.DELETE, "CITY_COEF", coef.getName(), String.valueOf(coef.getCoef()), null);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/used-coefs/{id}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<Void> deleteUsedCoef(@PathVariable long id) {
        var coef = byId(usedCoefs, id, "Коэффициент износа");
        usedCoefs.delete(coef);
        audit.record(AuditService.DELETE, "USED_COEF", coef.getYear() + "/" + coef.getKm(), String.valueOf(coef.getCoef()), null);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/drive-classes/{id}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<Void> deleteDriveClass(@PathVariable long id) {
        var driveClass = byId(driveClasses, id, "Класс водителя");
        driveClasses.delete(driveClass);
        audit.record(AuditService.DELETE, "DRIVE_CLASS", driveClass.getDriveClass(), String.valueOf(driveClass.getCoef()), null);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/directions/{id}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<Void> deleteDirection(@PathVariable long id) {
        var direction = byId(directions, id, "Направление");
        directions.delete(direction);
        audit.record(AuditService.DELETE, "DIRECTION", direction.getTitle(), String.valueOf(direction.getNumber()), null);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/route-tariffs/{id}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<Void> deleteRouteTariff(@PathVariable long id) {
        var tariff = byId(routeTariffs, id, "Тариф маршрута");
        routeTariffs.delete(tariff);
        audit.record(AuditService.DELETE, "ROUTE_TARIFF",
                tariff.getRouteId() + "/" + (tariff.getFuelId() == null ? "*" : tariff.getFuelId()),
                String.valueOf(tariff.getPricePer1Mkm()), null);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------ вспомогательное

    /** Запись справочника по идентификатору; нет такой — 404 с понятным текстом. */
    private static <T, ID> T byId(CrudRepository<T, ID> repo, ID id, String label) {
        return repo.findById(id).orElseThrow(() -> new NotFoundException(label + " не найден(а): " + id));
    }

    private static <T> ResponseEntity<T> saved(Optional<?> existing, T body) {
        return ResponseEntity.status(existing.isPresent() ? HttpStatus.OK : HttpStatus.CREATED).body(body);
    }
}
