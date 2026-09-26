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
            mOpt.ifPresent(m -> {
                result.put("fio", m.getFio());
                result.put("price", m.getPrice());
                result.put("routeSummary", m.getRouteSummary());
                result.put("issuedAt", m.getCreatedAt());
            });
            if (mOpt.isEmpty()) verdict = VerifyScanLog.NOT_FOUND;
        } else if (jti != null) {
            result.put("kind", "WAYBILL");
            var wbOpt = waybills.findById(UUID.fromString(jti));
            if (wbOpt.isPresent()) {
                var wb = wbOpt.get();
                onlineStatus = wb.getStatus().name();
                result.put("onlineStatus", onlineStatus);
                result.put("number", wb.getNumber());
                result.put("validTo", wb.getValidTo());
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
