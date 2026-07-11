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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tj.mintrans.epd.waybill.domain.Waybill;

import java.text.ParseException;
import java.util.Date;
import java.util.Map;

/**
 * Подписанная полезная нагрузка QR-кода (модель ISO 18013-5 / раздел 8.9 ТЗ):
 * JWS ES256; инспектор проверяет подпись офлайн по публичному ключу (JWKS).
 *
 * Ключ подписи — единственный якорь доверия всей дорожной проверки. В проде он ДОЛЖЕН быть
 * стабильным (одинаковым между рестартами и репликами), иначе ранее выданные QR и закешированный
 * инспекторами JWKS перестают проверяться, а разные реплики подписывают разными ключами.
 * Поэтому: если задан epd.qr.signing-key (стабильный EC JWK из Vault/HSM) — используется он;
 * иначе генерируется эфемерный dev-ключ, но при прод-режиме подписи (epd.signing.mode!=stub)
 * это запрещено (fail-fast на старте) — по аналогии с TitleSigner.
 */
@Service
public class QrTokenService {

    private static final Logger log = LoggerFactory.getLogger(QrTokenService.class);

    private final ECKey key;

    public QrTokenService(@Value("${epd.qr.signing-key:}") String signingKeyJwk,
                          @Value("${epd.signing.mode:stub}") String signingMode) throws JOSEException {
        if (signingKeyJwk != null && !signingKeyJwk.isBlank()) {
            ECKey parsed;
            try {
                parsed = ECKey.parse(signingKeyJwk);
            } catch (ParseException e) {
                throw new IllegalStateException("epd.qr.signing-key не является корректным EC JWK", e);
            }
            if (!parsed.isPrivate()) {
                throw new IllegalStateException(
                        "epd.qr.signing-key должен быть приватным EC JWK (с параметром d) для подписи QR");
            }
            this.key = parsed;
            log.info("QR: используется сконфигурированный стабильный ключ подписи (keyID={})", key.getKeyID());
        } else {
            // Fail-fast: в прод-режиме подписи эфемерный ключ недопустим (см. javadoc класса).
            if (!"stub".equalsIgnoreCase(signingMode)) {
                throw new IllegalStateException(
                        "epd.qr.signing-key не задан при epd.signing.mode=" + signingMode
                        + ": в проде ключ подписи QR должен быть стабильным (Vault/HSM), иначе рестарт или "
                        + "масштабирование ломают офлайн-проверку инспектором (ранее выданные QR и JWKS).");
            }
            this.key = new ECKeyGenerator(Curve.P_256).keyID("epd-dev-1").generate();
            log.warn("QR: DEV — эфемерный ключ подписи (меняется при каждом старте). "
                    + "Для прода задайте epd.qr.signing-key (стабильный EC JWK из Vault/HSM).");
        }
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
