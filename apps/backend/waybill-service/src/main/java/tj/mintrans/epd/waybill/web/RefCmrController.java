package tj.mintrans.epd.waybill.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tj.mintrans.epd.waybill.domain.Waybill;
import tj.mintrans.epd.waybill.domain.WaybillType;
import tj.mintrans.epd.waybill.repository.WaybillRepository;
import tj.mintrans.epd.waybill.service.QrTokenService;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Проверка СМР внешней системой — замена legacy {@code POST /api/cmr} ({@code ApiCmrController}; сверка
 * 25.09, G4): по номеру СМР — номер, дата, перевозчик, его РМА и ссылка на страницу проверки (QR).
 *
 * <p>В legacy доступ давал один общий токен, зашитый в код. Здесь — обычная учётка внешней системы
 * с каналом {@code ref} (страница «Пользователи»), токен {@code POST /api/v1/auth/token}; поле
 * {@code token} запроса игнорируется. СМР в платформе — лист 5Б-БМ (международная грузовая), номер
 * СМР — номер листа. Не найден — 404 {@code {error}} (legacy отвечал пустым телом с кодом 200).</p>
 */
@RestController
@RequestMapping("/api/v1/ref")
@PreAuthorize("hasAnyRole('API_INTEGRATOR','SYSTEM_ADMIN')")
public class RefCmrController {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final WaybillRepository waybills;
    private final QrTokenService qr;
    private final String publicBaseUrl;

    public RefCmrController(WaybillRepository waybills, QrTokenService qr,
                            @Value("${epd.public-base-url:http://localhost:3000}") String publicBaseUrl) {
        this.waybills = waybills;
        this.qr = qr;
        this.publicBaseUrl = publicBaseUrl.endsWith("/") ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1)
                : publicBaseUrl;
    }

    /** Номер в JSON-теле {@code {"cmr_number": …}}. */
    @PostMapping(value = "/cmr", consumes = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> cmrJson(@RequestBody Map<String, Object> body) {
        // Тело обязательно: иначе запрос без тела подходил бы под оба обработчика (consumes не проверяется).
        return cmr(body.get("cmr_number") != null ? body.get("cmr_number").toString() : null);
    }

    /** Номер полем формы или параметром запроса, как присылала система legacy. */
    @PostMapping("/cmr")
    public ResponseEntity<Map<String, Object>> cmr(@RequestParam(value = "cmr_number", required = false) String number) {
        if (number == null || number.isBlank()) {
            return error(HttpStatus.UNPROCESSABLE_ENTITY, "Параметр cmr_number обязателен.");
        }
        var wb = waybills.findByNumber(number.trim())
                .filter(w -> w.getWaybillType() == WaybillType.WB_TRUCK_INTL);
        if (wb.isEmpty()) {
            return error(HttpStatus.NOT_FOUND, "CMR not found");
        }
        return ResponseEntity.ok(row(wb.get()));
    }

    private Map<String, Object> row(Waybill wb) {
        var org = wb.getOrganizationSnapshot();
        var out = new LinkedHashMap<String, Object>();
        out.put("number", wb.getNumber());
        out.put("created_at", wb.getCreatedAt() == null ? null
                : TS.format(wb.getCreatedAt().atZoneSameInstant(ZoneId.systemDefault())));
        out.put("company", org == null || org.get("name") == null ? null : org.get("name").toString());
        out.put("rma", wb.getOrganizationRma());
        out.put("status", wb.getStatus().name());
        out.put("link", publicBaseUrl + "/verify/" + qr.sign(wb));
        return out;
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        var body = new LinkedHashMap<String, Object>();
        body.put("error", message);
        body.put("detail", message);
        return ResponseEntity.status(status).body(body);
    }
}
