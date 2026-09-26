package tj.mintrans.epd.waybill.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tj.mintrans.epd.waybill.domain.Waybill;
import tj.mintrans.epd.waybill.service.AggregatorService;
import tj.mintrans.epd.waybill.web.error.ApiErrors.ConflictException;
import tj.mintrans.epd.waybill.web.error.ApiErrors.FieldException;
import tj.mintrans.epd.waybill.web.error.ApiErrors.NotFoundException;
import tj.mintrans.epd.waybill.web.error.ApiErrors.UnprocessableException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Legacy-совместимый API агрегаторов такси (ЧУРА/Jura, НЕРУ/Neru) —
 * spec/notes/01-legacy-api-и-формы.md, раздел 7. Поля запроса/ответа — snake_case,
 * как в исходном /api/waybill_neru.
 *
 * <p>Сверка 25.09, G3: тела ответов — как у {@code Waybill3cController::waybill_neru} и
 * {@code StoreWaybill3cNeruRequest}, их разбирает НЕРУ:</p>
 * <ul>
 *   <li>новая заявка — 201 {@code {statusCode, message, waybill_id}};</li>
 *   <li>действующий подтверждённый лист — 200 {@code {statusCode, id, number, exit_date, …}};</li>
 *   <li>ошибки — {@code {statusCode, error}} (404, 409) и
 *       {@code {statusCode: 422, message, errors: {поле: [...]}}}.</li>
 * </ul>
 * <p>Для клиентов платформы в ошибках ещё {@code detail}, в заявке — {@code id} и {@code status}.</p>
 */
@RestController
@RequestMapping("/api/v1/aggregator/waybills")
public class AggregatorController {

    private static final String INVALID = "The given data was invalid.";
    private static final String DATE_FORMAT_MSG = "Дата и время должны быть в формате Y-m-d H:i или Y-m-d H:i:s";
    private static final Pattern DATE_TIME = Pattern.compile("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}(:\\d{2})?$");
    private static final Pattern RMA = Pattern.compile("^\\d{9,10}$");
    private static final DateTimeFormatter OUT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final AggregatorService service;

    public AggregatorController(AggregatorService service) {
        this.service = service;
    }

    // ------------------------------------------------------------- контракт

    /** Поля — строками: проверка и сообщения как в legacy, все ошибки разом в {@code errors}. */
    public record AggregatorCreateRequest(
            @JsonProperty("organization_rma") String organizationRma,
            @JsonProperty("transport_registration_number") String transportRegistrationNumber,
            @JsonProperty("driver_rma") String driverRma,
            @JsonProperty("employee_rma") String employeeRma,
            // Формат дат — как в исходном /api/waybill_neru: "2026-07-10 14:00" (секунды — по желанию)
            @JsonProperty("exit_date") String exitDate,
            @JsonProperty("entry_date") String entryDate,
            @JsonProperty("distance") Object distance) {
    }

    // ------------------------------------------------------------- эндпоинты

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody AggregatorCreateRequest req) {
        Map<String, List<String>> errors = new LinkedHashMap<>();
        require(errors, "organization_rma", req.organizationRma(), "Параметр organization_rma обязателен.");
        if (!blank(req.organizationRma()) && !RMA.matcher(req.organizationRma().trim()).matches()) {
            add(errors, "organization_rma", "organization_rma — 9 или 10 цифр.");
        }
        require(errors, "transport_registration_number", req.transportRegistrationNumber(),
                "Параметр transport_registration_number обязателен.");
        require(errors, "driver_rma", req.driverRma(), "Параметр driver_rma обязателен.");
        LocalDateTime exit = dateTime(errors, "exit_date", req.exitDate());
        LocalDateTime entry = dateTime(errors, "entry_date", req.entryDate());
        if (entry != null && entry.toLocalDate().isBefore(LocalDate.now())) {
            add(errors, "entry_date", "Дата въезда должна быть не ранее сегодняшней даты");
        }
        Integer distance = distance(errors, req.distance());
        if (!errors.isEmpty()) {
            return invalid(errors);
        }

        var result = service.submit(req.organizationRma().trim(), req.transportRegistrationNumber().trim(),
                req.driverRma().trim(), blank(req.employeeRma()) ? null : req.employeeRma().trim(),
                toOffset(exit), toOffset(entry), distance);
        if (!result.created()) {
            return ResponseEntity.ok(toResponse(result.waybill()));
        }
        var body = new LinkedHashMap<String, Object>();
        body.put("statusCode", 201);
        body.put("message", "Заявка на путевой лист принята. Необходимо подтверждение доктора и механика");
        body.put("waybill_id", result.waybill().getId());
        body.put("id", result.waybill().getId());
        body.put("status", "Ожидает");
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable UUID id) {
        return toResponse(service.getConfirmed(id));
    }

    // ------------------------------------------------------------- ошибки в legacy-виде

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<Map<String, Object>> notFound(NotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<Map<String, Object>> conflict(ConflictException e) {
        return error(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(UnprocessableException.class)
    ResponseEntity<Map<String, Object>> unprocessable(UnprocessableException e) {
        Map<String, List<String>> errors = new LinkedHashMap<>();
        add(errors, e instanceof FieldException f ? f.field() : "waybill", e.getMessage());
        return invalid(errors);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException e) {
        Map<String, List<String>> errors = new LinkedHashMap<>();
        add(errors, "body", "Тело запроса — JSON-объект с полями заявки.");
        return invalid(errors);
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        var body = new LinkedHashMap<String, Object>();
        body.put("statusCode", status.value());
        body.put("error", message);
        body.put("detail", message);
        return ResponseEntity.status(status).body(body);
    }

    private static ResponseEntity<Map<String, Object>> invalid(Map<String, List<String>> errors) {
        var body = new LinkedHashMap<String, Object>();
        body.put("statusCode", 422);
        body.put("message", INVALID);
        body.put("errors", errors);
        body.put("detail", errors.values().iterator().next().getFirst());
        return ResponseEntity.unprocessableEntity().body(body);
    }

    // ------------------------------------------------------------- проверка полей

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static void add(Map<String, List<String>> errors, String field, String message) {
        errors.computeIfAbsent(field, k -> new ArrayList<>()).add(message);
    }

    private static void require(Map<String, List<String>> errors, String field, String value, String message) {
        if (blank(value)) {
            add(errors, field, message);
        }
    }

    /** {@code Y-m-d H:i} или {@code Y-m-d H:i:s}; обе даты обязательны, как в legacy. */
    static LocalDateTime dateTime(Map<String, List<String>> errors, String field, String raw) {
        if (blank(raw)) {
            add(errors, field, "Параметр " + field + " обязателен.");
            return null;
        }
        String s = raw.trim();
        if (!DATE_TIME.matcher(s).matches()) {
            add(errors, field, DATE_FORMAT_MSG);
            return null;
        }
        try {
            return LocalDateTime.parse(s.length() == 16 ? s + ":00" : s, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (DateTimeParseException e) {
            add(errors, field, DATE_FORMAT_MSG);
            return null;
        }
    }

    private static Integer distance(Map<String, List<String>> errors, Object raw) {
        if (raw == null || raw.toString().isBlank()) {
            add(errors, "distance", "Параметр distance обязателен.");
            return null;
        }
        try {
            int d = raw instanceof Number n && n.doubleValue() == n.intValue() ? n.intValue()
                    : Integer.parseInt(raw.toString().trim());
            if (d < 0) {
                add(errors, "distance", "distance не может быть отрицательной.");
                return null;
            }
            return d;
        } catch (NumberFormatException e) {
            add(errors, "distance", "distance должна быть целым числом.");
            return null;
        }
    }

    // ------------------------------------------------------------- маппинг снимков

    /** Лист в виде legacy-ответа waybill_neru; {@code status} — наш, для наглядности. */
    static Map<String, Object> toResponse(Waybill wb) {
        var org = wb.getOrganizationSnapshot();
        var vehicle = wb.getVehicleSnapshot();
        var driver = wb.getDriverSnapshot();
        Integer counterExit = wb.getOdometerExit() != null
                ? wb.getOdometerExit()
                : intOrNull(vehicle == null ? null : vehicle.get("odometer"));

        var company = new LinkedHashMap<String, Object>();
        company.put("id", str(org, "id"));
        company.put("rma", str(org, "rma"));
        company.put("name", str(org, "name"));
        company.put("kpp", str(org, "kpp"));
        var parking = new LinkedHashMap<String, Object>();
        parking.put("id", str(vehicle, "id"));
        parking.put("registration_number", str(vehicle, "registrationNumber"));
        parking.put("vincode", str(vehicle, "vincode"));
        parking.put("transport_type_id", intOrNull(vehicle == null ? null : vehicle.get("transportType")));
        var timesheet = new LinkedHashMap<String, Object>();
        timesheet.put("id", str(driver, "id"));
        timesheet.put("full_name", str(driver, "fullName"));
        timesheet.put("rma", str(driver, "rma"));

        var body = new LinkedHashMap<String, Object>();
        body.put("statusCode", 200);
        body.put("id", wb.getId());
        body.put("number", wb.getNumber());
        body.put("exit_date", format(wb.getValidFrom()));
        body.put("entry_date", format(wb.getValidTo()));
        body.put("indication_counter_exit", counterExit);
        body.put("company", company);
        body.put("parking", parking);
        body.put("timesheet", timesheet);
        body.put("status", statusLabel(wb));
        return body;
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

    private static String format(OffsetDateTime t) {
        return t == null ? null : OUT.format(t.atZoneSameInstant(ZoneId.systemDefault()));
    }

    private static String str(Map<String, Object> snapshot, String key) {
        Object value = snapshot == null ? null : snapshot.get(key);
        return value == null ? null : value.toString();
    }

    private static Integer intOrNull(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return o != null ? Integer.valueOf(o.toString()) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static OffsetDateTime toOffset(LocalDateTime dt) {
        return dt == null ? null : dt.atZone(ZoneId.systemDefault()).toOffsetDateTime();
    }
}
