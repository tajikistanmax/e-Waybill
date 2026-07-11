package tj.mintrans.epd.waybill.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tj.mintrans.epd.waybill.domain.GpsPing;
import tj.mintrans.epd.waybill.repository.GpsPingRepository;
import tj.mintrans.epd.waybill.web.error.ApiErrors.NotFoundException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Приём GPS-координат от устройств/трекеров и выдача последней позиции
 * и трека по путевому листу.
 */
@RestController
@RequestMapping("/api/v1/gps")
public class GpsController {

    private final GpsPingRepository repository;

    public GpsController(GpsPingRepository repository) {
        this.repository = repository;
    }

    // ------------------------------------------------------------- запросы

    public record GpsPingRequest(
            @NotBlank String vehicleRegNumber,
            @NotNull BigDecimal lat,
            @NotNull BigDecimal lon,
            Short speedKmh,
            UUID waybillId,
            OffsetDateTime recordedAt) {
    }

    // ------------------------------------------------------------- эндпоинты

    /** Приём одного GPS-пинга от устройства. */
    @PostMapping
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

    /** Последняя известная позиция ТС по госномеру. */
    @GetMapping("/last")
    public GpsPing last(@RequestParam String vehicleRegNumber) {
        var reg = vehicleRegNumber.trim().toUpperCase();
        return repository.findTop1ByVehicleRegNumberOrderByRecordedAtDesc(reg)
                .orElseThrow(() -> new NotFoundException("Позиция для ТС %s не найдена".formatted(reg)));
    }

    /** Трек (последовательность точек) по путевому листу. */
    @GetMapping("/track")
    public List<GpsPing> track(@RequestParam UUID waybillId) {
        return repository.findTop500ByWaybillIdOrderByRecordedAtAsc(waybillId);
    }
}
