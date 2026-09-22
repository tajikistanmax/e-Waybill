package tj.mintrans.epd.waybill.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tj.mintrans.epd.waybill.client.MasterDataClient;
import tj.mintrans.epd.waybill.config.TenantScope;
import tj.mintrans.epd.waybill.domain.GpsPing;
import tj.mintrans.epd.waybill.domain.Waybill;
import tj.mintrans.epd.waybill.domain.WaybillStatus;
import tj.mintrans.epd.waybill.domain.GpsEventState;
import tj.mintrans.epd.waybill.repository.GpsPingRepository;
import tj.mintrans.epd.waybill.repository.WaybillRepository;
import tj.mintrans.epd.waybill.service.GpsEventService;
import tj.mintrans.epd.waybill.web.error.ApiErrors.NotFoundException;
import tj.mintrans.epd.waybill.web.error.ApiErrors.UnprocessableException;
import com.fasterxml.jackson.annotation.JsonAlias;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Приём GPS-координат от устройств/трекеров и выдача последней позиции и трека по ПЛ.
 * Приём — только сервисный аккаунт трекеров (API_INTEGRATOR); чтение тенант-скоупится
 * по организации ТС/ПЛ (иначе — межарендная утечка геоданных). Платформенные роли
 * (инспектор/аналитик/админ/сервис) видят все организации.
 */
@RestController
@RequestMapping("/api/v1/gps")
public class GpsController {

    private final GpsPingRepository repository;
    private final WaybillRepository waybills;
    private final MasterDataClient masterData;
    private final TenantScope tenantScope;
    private final GpsEventService gpsEvents;

    public GpsController(GpsPingRepository repository, WaybillRepository waybills,
                         MasterDataClient masterData, TenantScope tenantScope, GpsEventService gpsEvents) {
        this.repository = repository;
        this.waybills = waybills;
        this.masterData = masterData;
        this.tenantScope = tenantScope;
        this.gpsEvents = gpsEvents;
    }

    /**
     * GPS-событие Smart-city (MIGRATION.md 9.7 / 12.12 — legacy {@code POST /api/gps/gps_data}): поля принимаются
     * и в legacy-именах ({@code registration_number}, {@code distance}), состояние — snake_case или enum.
     */
    public record GpsEventRequest(
            @JsonAlias("registration_number") @NotBlank String vehicleRegNumber,
            @NotBlank String state,
            String direction,
            @JsonAlias("distance") BigDecimal distanceKm,
            OffsetDateTime eventTime) {
    }

    /** Регистрация события заезда/выезда (маршрут / предприятие). Только сервисный аккаунт интеграции. */
    @PostMapping("/events")
    @PreAuthorize("hasAnyRole('API_INTEGRATOR','SYSTEM_ADMIN')")
    public ResponseEntity<GpsEventService.Result> registerEvent(@Valid @RequestBody GpsEventRequest req) {
        GpsEventState state = GpsEventState.parse(req.state());
        if (state == null) {
            throw new UnprocessableException("state: допустимые значения enter_into_route, exit_from_route, enter_into_company, exit_from_company");
        }
        var result = gpsEvents.register(new GpsEventService.Request(
                req.vehicleRegNumber(), state, req.direction(), req.distanceKm(), req.eventTime()));
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    /** Журнал GPS-событий (legacy admin/gpsevent, MIGRATION.md 8.9): фильтр по организации/ТС/состоянию/периоду. */
    @GetMapping("/events")
    @PreAuthorize("hasAnyRole('DISPATCHER','COMPANY_ADMIN','SYSTEM_ADMIN','INSPECTOR','MINTRANS_ANALYST')")
    public GpsEventService.PageResult events(@RequestParam(required = false) String organizationRma,
                                             @RequestParam(required = false) String vehicleRegNumber,
                                             @RequestParam(required = false) String state,
                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                             @RequestParam(required = false) String q,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "50") int size) {
        return gpsEvents.list(new GpsEventService.Filter(organizationRma, vehicleRegNumber, GpsEventState.parse(state), from, to, q),
                page, size);
    }

    public record GpsPingRequest(
            @NotBlank String vehicleRegNumber,
            @NotNull BigDecimal lat,
            @NotNull BigDecimal lon,
            Short speedKmh,
            UUID waybillId,
            OffsetDateTime recordedAt) {
    }

    /** Приём одного GPS-пинга. Только сервисный аккаунт трекеров/интеграции. */
    @PostMapping
    @PreAuthorize("hasAnyRole('API_INTEGRATOR','SYSTEM_ADMIN')")
    public ResponseEntity<GpsPing> ingest(@Valid @RequestBody GpsPingRequest req) {
        var ping = new GpsPing();
        ping.setVehicleRegNumber(req.vehicleRegNumber().trim().toUpperCase());
        ping.setLat(req.lat());
        ping.setLon(req.lon());
        ping.setSpeedKmh(req.speedKmh());
        ping.setWaybillId(req.waybillId());
        ping.setRecordedAt(req.recordedAt() == null ? OffsetDateTime.now() : req.recordedAt());
        var saved = repository.save(ping);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    /** Последняя известная позиция ТС по госномеру (только своя организация для тенанта). */
    @GetMapping("/last")
    public GpsPing last(@RequestParam String vehicleRegNumber) {
        var reg = vehicleRegNumber.trim().toUpperCase();
        assertVehicleVisible(reg);
        return repository.findTop1ByVehicleRegNumberOrderByRecordedAtDesc(reg)
                .orElseThrow(() -> new NotFoundException("Позиция для ТС %s не найдена".formatted(reg)));
    }

    /** Позиция ТС на линии для монитора диспетчера: ПЛ + последняя координата. */
    public record LivePosition(String vehicleRegNumber, String number, String driver, String status,
                               String waybillType,
                               String organizationRma, String organizationName,
                               BigDecimal lat, BigDecimal lon, Short speedKmh, OffsetDateTime recordedAt) {
    }

    /**
     * Живой мониторинг: все ТС «на линии» (выданные/активные ПЛ) с последней GPS-координатой.
     * Тенант — только своя организация; платформенные роли (инспектор/аналитик/админ) — все.
     * Позиция может быть null, если от трекера ТС ещё не поступало пингов.
     */
    @GetMapping("/live")
    @PreAuthorize("hasAnyRole('DISPATCHER','COMPANY_ADMIN','SYSTEM_ADMIN','INSPECTOR','MINTRANS_ANALYST')")
    public List<LivePosition> live() {
        var onLine = EnumSet.of(WaybillStatus.ISSUED, WaybillStatus.ACTIVE);
        List<Waybill> active;
        if (tenantScope.isBounded()) {
            var scope = tenantScope.rmas();
            if (scope.isEmpty() || scope.contains("__none__")) {
                return List.of();
            }
            active = waybills.findByOrganizationRmaInOrderByCreatedAtDesc(scope).stream()
                    .filter(w -> onLine.contains(w.getStatus())).toList();
        } else {
            active = waybills.findByStatusInOrderByCreatedAtDesc(onLine);
        }
        return active.stream().map(w -> {
            var ping = repository.findTop1ByVehicleRegNumberOrderByRecordedAtDesc(w.getVehicleRegNumber()).orElse(null);
            Map<String, Object> ds = w.getDriverSnapshot();
            String driver = ds != null && ds.get("fullName") != null ? String.valueOf(ds.get("fullName")) : w.getDriverRma();
            Map<String, Object> os = w.getOrganizationSnapshot();
            String orgName = os != null && os.get("name") != null ? String.valueOf(os.get("name")) : w.getOrganizationRma();
            return new LivePosition(
                    w.getVehicleRegNumber(), w.getNumber(), driver, w.getStatus().name(),
                    w.getWaybillType() != null ? w.getWaybillType().name() : null,
                    w.getOrganizationRma(), orgName,
                    ping != null ? ping.getLat() : null,
                    ping != null ? ping.getLon() : null,
                    ping != null ? ping.getSpeedKmh() : null,
                    ping != null ? ping.getRecordedAt() : null);
        }).toList();
    }

    /** Трек по путевому листу (только своя организация для тенанта). */
    @GetMapping("/track")
    public List<GpsPing> track(@RequestParam UUID waybillId) {
        if (tenantScope.isBounded()) {
            var wb = waybills.findById(waybillId)
                    .orElseThrow(() -> new NotFoundException("Трек не найден"));
            if (!tenantScope.contains(wb.getOrganizationRma())) {
                throw new NotFoundException("Трек не найден");
            }
        }
        return repository.findTop500ByWaybillIdOrderByRecordedAtAsc(waybillId);
    }

    /**
     * Мультиарендность чтения /last: тенант видит позицию только ТС своей организации
     * ИЛИ филиалов (tenantScope, как в live()/track()) — раньше сравнивалось только с
     * собственной rma вызывающего, из-за чего COMPANY_ADMIN не видел позицию ТС филиала.
     * Организацию ТС резолвим из master-data (organizationId ТС ↔ id одной из организаций
     * области видимости). Отсутствие/чужая организация → 404 (не раскрываем существование позиции).
     */
    private void assertVehicleVisible(String reg) {
        if (!tenantScope.isBounded()) {
            return;
        }
        var vehicle = masterData.findVehicle(reg)
                .orElseThrow(() -> new NotFoundException("Позиция для ТС %s не найдена".formatted(reg)));
        String vehicleOrgId = String.valueOf(vehicle.get("organizationId"));
        boolean visible = tenantScope.rmas().stream()
                .map(masterData::findOrganization)
                .flatMap(Optional::stream)
                .anyMatch(org -> vehicleOrgId.equals(String.valueOf(org.get("id"))));
        if (!visible) {
            throw new NotFoundException("Позиция для ТС %s не найдена".formatted(reg));
        }
    }
}
