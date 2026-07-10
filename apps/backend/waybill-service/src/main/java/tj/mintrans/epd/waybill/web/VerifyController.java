package tj.mintrans.epd.waybill.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import tj.mintrans.epd.waybill.repository.WaybillRepository;
import tj.mintrans.epd.waybill.service.QrTokenService;
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

    public VerifyController(QrTokenService qr, WaybillRepository waybills) {
        this.qr = qr;
        this.waybills = waybills;
    }

    @GetMapping("/api/v1/verify/{jws}")
    public Map<String, Object> verify(@PathVariable String jws) {
        try {
            var claims = qr.verify(jws);
            var result = new LinkedHashMap<String, Object>();
            result.put("signatureValid", true);
            result.put("claims", claims);
            var jti = claims.get("jti");
            if (jti != null) {
                waybills.findById(UUID.fromString(jti.toString())).ifPresent(wb -> {
                    result.put("onlineStatus", wb.getStatus().name());
                    result.put("number", wb.getNumber());
                    result.put("validTo", wb.getValidTo());
                });
            }
            return result;
        } catch (Exception e) {
            throw new UnprocessableException("QR-код недействителен: " + e.getMessage());
        }
    }

    /** Публичные ключи для офлайн-приложений инспекторов. */
    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> jwks() {
        return qr.jwks();
    }
}
