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
import tj.mintrans.epd.waybill.config.CurrentUser;
import tj.mintrans.epd.waybill.domain.GpsPing;
import tj.mintrans.epd.waybill.repository.GpsPingRepository;
import tj.mintrans.epd.waybill.repository.WaybillRepository;
import tj.mintrans.epd.waybill.web.error.ApiErrors.NotFoundException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
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
    private final CurrentUser currentUser;

    public GpsController(GpsPingRepository repository, WaybillRepository waybills,
                         MasterDataClient masterData, CurrentUser currentUser) {
        this.repository = repository;
        this.waybills = waybills;
        this.masterData = masterData;
        this.currentUser = currentUser;
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

    /** Трек по путевому листу (только своя организация для тенанта). */
    @GetMapping("/track")
    public List<GpsPing> track(@RequestParam UUID waybillId) {
        if (currentUser.isTenantScoped()) {
            var wb = waybills.findById(waybillId)
                    .orElseThrow(() -> new NotFoundException("Трек не найден"));
            boolean own = currentUser.organizationRma()
                    .map(rma -> rma.equals(wb.getOrganizationRma())).orElse(false);
            if (!own) {
                throw new NotFoundException("Трек не найден");
            }
        }
        return repository.findTop500ByWaybillIdOrderByRecordedAtAsc(waybillId);
    }

    /**
     * Мультиарендность чтения /last: тенант видит позицию только ТС своей организации.
     * Организацию ТС резолвим из master-data (organizationId ТС ↔ id организации вызывающего).
     * Отсутствие/чужая организация → 404 (не раскрываем существование позиции).
     */
    private void assertVehicleVisible(String reg) {
        if (!currentUser.isTenantScoped()) {
            return;
        }
        String callerRma = currentUser.organizationRma().orElse(null);
        if (callerRma == null) {
            throw new NotFoundException("Позиция для ТС %s не найдена".formatted(reg));
        }
        var vehicle = masterData.findVehicle(reg)
                .orElseThrow(() -> new NotFoundException("Позиция для ТС %s не найдена".formatted(reg)));
        var callerOrg = masterData.findOrganization(callerRma).orElse(null);
        if (callerOrg == null
                || !String.valueOf(vehicle.get("organizationId")).equals(String.valueOf(callerOrg.get("id")))) {
            throw new NotFoundException("Позиция для ТС %s не найдена".formatted(reg));
        }
    }
}
