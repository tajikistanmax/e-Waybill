package tj.mintrans.epd.waybill.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import tj.mintrans.epd.waybill.domain.VerifyScanLog;
import tj.mintrans.epd.waybill.repository.MalumotnomaRepository;
import tj.mintrans.epd.waybill.repository.WaybillRepository;
import tj.mintrans.epd.waybill.service.QrTokenService;
import tj.mintrans.epd.waybill.service.VerifyScanLogService;
import tj.mintrans.epd.waybill.web.error.ApiErrors.UnprocessableException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Публичный контур проверки (прообраз QR Verification Service):
 * офлайн-проверку выполняет приложение инспектора по JWKS; здесь — онлайн-проверка + актуальный статус.
 */
@RestController
public class VerifyController {

    private final QrTokenService qr;
    private final WaybillRepository waybills;
    private final MalumotnomaRepository malumotnomas;
    private final VerifyScanLogService scanLog;
    private tj.mintrans.epd.waybill.service.LegacyQrService legacyQr;

    public VerifyController(QrTokenService qr, WaybillRepository waybills, MalumotnomaRepository malumotnomas,
                           VerifyScanLogService scanLog) {
        this.qr = qr;
        this.waybills = waybills;
        this.malumotnomas = malumotnomas;
        this.scanLog = scanLog;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void setLegacyQr(tj.mintrans.epd.waybill.service.LegacyQrService legacyQr) {
        this.legacyQr = legacyQr;
    }

    private tj.mintrans.epd.waybill.repository.ConsignmentNoteRepository notes;

    /** Борхаты — для проверки собственного QR борхата (сверка 25.09, B6). */
    @org.springframework.beans.factory.annotation.Autowired
    void setNotes(tj.mintrans.epd.waybill.repository.ConsignmentNoteRepository notes) {
        this.notes = notes;
    }

    @GetMapping("/api/v1/verify/{jws}")
    public Map<String, Object> verify(@PathVariable String jws, HttpServletRequest request) {
        // Проверка подписи/срока изолирована: её сбой — это именно «QR недействителен»
        // (в отличие от «документ не найден» ниже). Фиксируем попытку и отвечаем как раньше.
        Map<String, Object> claims;
        try {
            claims = qr.verify(jws);
        } catch (Exception e) {
            scanLog.record(null, null, VerifyScanLog.SIGNATURE_INVALID, null, request);
            throw new UnprocessableException("QR-код недействителен: " + e.getMessage());
        }

        var result = new LinkedHashMap<String, Object>();
        result.put("signatureValid", true);
        result.put("claims", claims);

        var jtiObj = claims.get("jti");
        String jti = jtiObj != null ? jtiObj.toString() : null;
        String number = claims.get("num") != null ? claims.get("num").toString() : null;
        boolean isMalumotnoma = "MALUMOTNOMA".equals(claims.get("typ"));

        String verdict = VerifyScanLog.VALID;
        String onlineStatus = null;

        if (jti != null && isMalumotnoma) {
            result.put("kind", "MALUMOTNOMA");
            var mOpt = malumotnomas.findById(UUID.fromString(jti));
            if (mOpt.isEmpty()) {
                // Подпись верна, но справки нет — не «действительна» с пустыми полями (было).
                scanLog.record(jti, number, VerifyScanLog.NOT_FOUND, null, request);
                throw new tj.mintrans.epd.waybill.web.error.ApiErrors.NotFoundException("Справка не найдена");
            }
            var m = mOpt.get();
            number = m.getNumber() == null ? number : m.getNumber().toString();
            result.put("number", number);
            result.put("fio", m.getFio());
            result.put("price", m.getPrice());
            result.put("routeSummary", m.getRouteSummary());
            result.put("issuedAt", m.getCreatedAt());
            result.put("transportTypeId", m.getTransportTypeId());
            result.put("privileged", m.getAge() == 1);
            if (m.isAnnulled()) {
                onlineStatus = "ANNULLED";
                result.put("annulled", true);
                result.put("annulledAt", m.getAnnulledAt());
            }
        } else if (jti != null && "CONSIGNMENT_NOTE".equals(claims.get("typ"))) {
            // Борхат (legacy qr/cargowaybill: дата, №, лист, отправитель, плательщик, получатель, груз).
            result.put("kind", "CONSIGNMENT_NOTE");
            var n = notes == null ? null : notes.findById(UUID.fromString(jti)).orElse(null);
            var wbOpt = n == null ? java.util.Optional.<tj.mintrans.epd.waybill.domain.Waybill>empty()
                    : waybills.findById(n.getWaybillId());
            if (n == null || wbOpt.isEmpty()) {
                scanLog.record(jti, number, VerifyScanLog.NOT_FOUND, null, request);
                throw new tj.mintrans.epd.waybill.web.error.ApiErrors.NotFoundException("Борхат не найден");
            }
            var wb = wbOpt.get();
            onlineStatus = wb.getStatus().name();
            result.put("onlineStatus", onlineStatus);
            result.put("number", n.getNumber());
            result.put("noteDate", n.getNoteDate());
            result.put("waybillNumber", wb.getNumber());
            result.put("vehicle", wb.getVehicleRegNumber());
            result.put("payerName", n.getPayerName());
            result.put("senderName", n.getSenderName());
            result.put("receiverName", n.getReceiverName());
            result.put("receiverAddress", n.getReceiverAddress());
            result.put("cargoName", n.getCargoName());
            result.put("cargoWeight", n.getCargoWeight());
            result.put("trips", n.getKind() == 2 ? null : n.getTrips());
            number = wb.getNumber();
        } else if (jti != null) {
            result.put("kind", "WAYBILL");
            var wbOpt = waybills.findById(UUID.fromString(jti));
            if (wbOpt.isPresent()) {
                var wb = wbOpt.get();
                onlineStatus = wb.getStatus().name();
                result.put("onlineStatus", onlineStatus);
                result.put("number", wb.getNumber());
                result.put("validTo", wb.getValidTo());
                putDetails(result, wb);
                if (number == null) number = wb.getNumber();
            } else {
                verdict = VerifyScanLog.NOT_FOUND;
            }
        }

        // Журналирование — вспомогательный след §18: record() никогда не бросает исключений,
        // поэтому ни сорвать, ни изменить ответ проверки оно не может.
        scanLog.record(jti, number, verdict, onlineStatus, request);
        return result;
    }

    private tj.mintrans.epd.waybill.client.MasterDataClient masterData;

    /** Фото водителя и настройка его показа — из master-data (сверка 25.09, G6). */
    @org.springframework.beans.factory.annotation.Autowired
    void setMasterData(tj.mintrans.epd.waybill.client.MasterDataClient masterData) {
        this.masterData = masterData;
    }

    /**
     * Сведения для сверки на месте — как legacy {@code qr/waybill.blade.php} (сверка 25.09, G6): маршрут,
     * стоянка, карта контроля, водительское удостоверение (категории, срок, номер — последние 4 знака) и
     * одобренное фото водителя, если его показ не выключен в «Настройки → Безопасность».
     *
     * <p>Паспорт водителя и сканы документов, которые legacy показывал на открытой странице, не выводятся:
     * это персональные данные, а для сверки водителя с документом хватает фото и ВУ.</p>
     */
    private void putDetails(Map<String, Object> result, tj.mintrans.epd.waybill.domain.Waybill wb) {
        var vehicle = wb.getVehicleSnapshot();
        var driver = wb.getDriverSnapshot();
        result.put("route", wb.getRoute());
        result.put("parkingNumber", str(vehicle, "parkingNumber"));
        result.put("controlCardNumber", str(vehicle, "controlCardNumber"));
        result.put("controlCardValidTo", str(vehicle, "controlCardValidTo"));
        result.put("licenseNumber", maskTail(str(driver, "licenseNumber")));
        result.put("licenseCategories", str(driver, "licenseCategories"));
        result.put("licenseValidTo", str(driver, "licenseValidTo"));
        if (masterData != null && !"false".equalsIgnoreCase(
                masterData.securitySettings().getOrDefault("verify_driver_photo", "true"))) {
            masterData.findDriverPhotoDataUri(wb.getDriverRma()).ifPresent(p -> result.put("driverPhoto", p));
        }
    }

    /** «•••• 0201» — достаточно, чтобы сверить с удостоверением в руках, и не раскрывает номер целиком. */
    static String maskTail(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        return v.length() <= 4 ? "••••" : "•••• " + v.substring(v.length() - 4);
    }

    private static String str(Map<String, Object> snapshot, String key) {
        Object v = snapshot == null ? null : snapshot.get(key);
        return v == null || v.toString().isBlank() ? null : v.toString();
    }

    /**
     * Старый бумажный QR «Роҳхат» ({@code /qrcode/{type}/{token}}) → токен проверки перенесённого листа
     * (сверка 25.09, G5). Портал проверки открывает по нему обычную страницу {@code /verify/{jws}}.
     */
    @GetMapping("/api/v1/verify/legacy/{type}/{token}")
    public Map<String, Object> legacy(@PathVariable String type, @PathVariable String token) {
        return Map.of("jws", legacyQr.resolve(type, token));
    }

    /** Публичные ключи для офлайн-приложений инспекторов. */
    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> jwks() {
        return qr.jwks();
    }
}
