package tj.mintrans.epd.waybill.web;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tj.mintrans.epd.waybill.domain.Waybill;
import tj.mintrans.epd.waybill.service.AggregatorService;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

/**
 * Legacy-совместимый API агрегаторов такси (ЧУРА/Jura, НЕРУ/Neru) —
 * spec/notes/01-legacy-api-и-формы.md, раздел 7. Поля запроса/ответа — snake_case,
 * как в исходном /api/waybill_neru.
 */
@RestController
@RequestMapping("/api/v1/aggregator/waybills")
public class AggregatorController {

    private final AggregatorService service;

    public AggregatorController(AggregatorService service) {
        this.service = service;
    }

    // ------------------------------------------------------------- контракт

    public record AggregatorCreateRequest(
            @JsonProperty("organization_rma") @NotBlank String organizationRma,
            @JsonProperty("transport_registration_number") @NotBlank String transportRegistrationNumber,
            @JsonProperty("driver_rma") @NotBlank String driverRma,
            @JsonProperty("employee_rma") String employeeRma,
            // Формат дат — как в исходном /api/waybill_neru: "2026-07-10 14:00"
            @JsonProperty("exit_date") @NotNull
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm") LocalDateTime exitDate,
            @JsonProperty("entry_date")
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm") LocalDateTime entryDate,
            @JsonProperty("distance") @NotNull @Min(0) Integer distance) {
    }

    public record AggregatorCreatedResponse(UUID id, String status, String message) {
    }

    public record CompanyDto(String id, String rma, String name, String kpp) {
    }

    public record ParkingDto(String id,
                             @JsonProperty("registration_number") String registrationNumber,
                             String vincode) {
    }

    public record TimesheetDto(String id,
                               @JsonProperty("full_name") String fullName,
                               String rma) {
    }

    public record AggregatorWaybillResponse(
            UUID id,
            String number,
            @JsonProperty("exit_date")
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm") OffsetDateTime exitDate,
            @JsonProperty("entry_date")
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm") OffsetDateTime entryDate,
            @JsonProperty("indication_counter_exit") Integer indicationCounterExit,
            CompanyDto company,
            ParkingDto parking,
            TimesheetDto timesheet,
            String status) {
    }

    // ------------------------------------------------------------- эндпоинты

    @PostMapping
    public ResponseEntity<AggregatorCreatedResponse> create(@Valid @RequestBody AggregatorCreateRequest req) {
        var wb = service.create(req.organizationRma(), req.transportRegistrationNumber(), req.driverRma(),
                req.employeeRma(), toOffset(req.exitDate()), toOffset(req.entryDate()), req.distance());
        return ResponseEntity.status(HttpStatus.CREATED).body(new AggregatorCreatedResponse(
                wb.getId(), "Ожидает",
                "Заявка на путевой лист принята. Необходимо подтверждение доктора/механика"));
    }

    @GetMapping("/{id}")
    public AggregatorWaybillResponse get(@PathVariable UUID id) {
        var wb = service.getConfirmed(id);
        return toResponse(wb);
    }

    // ------------------------------------------------------------- маппинг снимков

    private static AggregatorWaybillResponse toResponse(Waybill wb) {
        var org = wb.getOrganizationSnapshot();
        var vehicle = wb.getVehicleSnapshot();
        var driver = wb.getDriverSnapshot();
        Integer counterExit = wb.getOdometerExit() != null
                ? wb.getOdometerExit()
                : intOrNull(vehicle == null ? null : vehicle.get("odometer"));
        return new AggregatorWaybillResponse(
                wb.getId(),
                wb.getNumber(),
                wb.getValidFrom(),
                wb.getValidTo(),
                counterExit,
                new CompanyDto(str(org, "id"), str(org, "rma"), str(org, "name"), str(org, "kpp")),
                new ParkingDto(str(vehicle, "id"), str(vehicle, "registrationNumber"), str(vehicle, "vincode")),
                new TimesheetDto(str(driver, "id"), str(driver, "fullName"), str(driver, "rma")),
                statusLabel(wb));
    }

    /** Реальный статус ПЛ (не захардкоженный): getConfirmed уже отсекает не-действующие. */
    private static String statusLabel(Waybill wb) {
        return switch (wb.getStatus()) {
            case READY -> "Готов к выдаче";
            case ISSUED -> "Выдан";
            case ACTIVE -> "Активный";
            default -> wb.getStatus().name();
        };
    }

    private static String str(Map<String, Object> snapshot, String key) {
        Object value = snapshot == null ? null : snapshot.get(key);
        return value == null ? null : value.toString();
    }

    private static Integer intOrNull(Object o) {
        return o instanceof Number n ? n.intValue() : o != null ? Integer.valueOf(o.toString()) : null;
    }

    private static OffsetDateTime toOffset(LocalDateTime dt) {
        return dt == null ? null : dt.atZone(ZoneId.systemDefault()).toOffsetDateTime();
    }
}
