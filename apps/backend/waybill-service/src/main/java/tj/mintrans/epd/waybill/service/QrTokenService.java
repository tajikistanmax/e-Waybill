package tj.mintrans.epd.waybill.service;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.stereotype.Service;
import tj.mintrans.epd.waybill.domain.Waybill;

import java.text.ParseException;
import java.util.Date;
import java.util.Map;

/**
 * Подписанная полезная нагрузка QR-кода (модель ISO 18013-5 / раздел 8.9 ТЗ):
 * JWS ES256; инспектор проверяет подпись офлайн по публичному ключу (JWKS).
 * Dev-режим: ключ генерируется при старте. Prod: ключи в Crypto Service/HSM с ротацией.
 */
@Service
public class QrTokenService {

    private final ECKey key;

    public QrTokenService() throws JOSEException {
        this.key = new ECKeyGenerator(Curve.P_256).keyID("epd-dev-1").generate();
    }

    public String sign(Waybill wb) {
        try {
            var claims = new JWTClaimsSet.Builder()
                    .issuer("epd.tj")
                    .jwtID(wb.getId().toString())
                    .claim("num", wb.getNumber())
                    .claim("typ", wb.getWaybillType().name())
                    .claim("veh", wb.getVehicleRegNumber())
                    .claim("drv", wb.getDriverSnapshot() != null ? wb.getDriverSnapshot().get("fullName") : null)
                    .claim("org", wb.getOrganizationSnapshot() != null ? wb.getOrganizationSnapshot().get("name") : null)
                    .claim("med", wb.isMedPassed())
                    .claim("tec", wb.isTechPassed())
                    .notBeforeTime(wb.getValidFrom() != null ? Date.from(wb.getValidFrom().toInstant()) : new Date())
                    .expirationTime(wb.getValidTo() != null ? Date.from(wb.getValidTo().toInstant()) : null)
                    .build();
            var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(key.getKeyID()).build(), claims);
            jwt.sign(new ECDSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("Не удалось подписать QR-нагрузку", e);
        }
    }

    /** Офлайн-проверка подписи + срока (то же делает приложение инспектора без сети). */
    public Map<String, Object> verify(String jws) throws ParseException, JOSEException {
        var jwt = SignedJWT.parse(jws);
        if (!jwt.verify(new ECDSAVerifier(key.toECPublicKey()))) {
            throw new JOSEException("Подпись недействительна");
        }
        var claims = jwt.getJWTClaimsSet();
        // Проверка временных ограничений (exp/nbf) с допуском 60 с на рассинхрон часов —
        // просроченный или ещё не действующий QR недействителен даже при верной подписи.
        long now = System.currentTimeMillis();
        long skew = 60_000L;
        var exp = claims.getExpirationTime();
        var nbf = claims.getNotBeforeTime();
        if (exp != null && now > exp.getTime() + skew) {
            throw new JOSEException("Срок действия путевого листа истёк");
        }
        if (nbf != null && now < nbf.getTime() - skew) {
            throw new JOSEException("Путевой лист ещё не действует");
        }
        return claims.toJSONObject();
    }

    /** Публичные ключи для офлайн-приложений инспекторов. */
    public Map<String, Object> jwks() {
        return new JWKSet(key.toPublicJWK()).toJSONObject();
    }
}
