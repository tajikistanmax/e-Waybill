package tj.mintrans.epd.masterdata.auth;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tj.mintrans.epd.masterdata.domain.AppUser;

import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Выпуск токена доступа платформы.
 *
 * <p>Состав claim'ов повторяет прежний токен Keycloak один в один — именно поэтому при
 * отказе от Keycloak не пришлось трогать ни одну проверку прав: обе службы по-прежнему
 * читают роли из {@code realm_access.roles}, организацию из {@code organization_rma},
 * ИНН сотрудника из {@code rma}, имя из {@code preferred_username}.</p>
 */
@Component
public class TokenIssuer {

    private final SigningKeys keys;
    private final String issuer;
    private final long accessTtlSeconds;

    public TokenIssuer(SigningKeys keys,
                       @Value("${epd.auth.issuer:http://master-data:8081/api/v1/auth}") String issuer,
                       @Value("${epd.auth.access-ttl-seconds:900}") long accessTtlSeconds) {
        this.keys = keys;
        this.issuer = issuer;
        this.accessTtlSeconds = accessTtlSeconds;
    }

    public String issuer() {
        return issuer;
    }

    public long accessTtlSeconds() {
        return accessTtlSeconds;
    }

    /** Подписанный токен доступа для пользователя. */
    public String accessToken(AppUser user) {
        Instant now = Instant.now();
        Map<String, Object> realmAccess = new LinkedHashMap<>();
        realmAccess.put("roles", user.roleList());

        var builder = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(user.getId().toString())
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(accessTtlSeconds)))
                .claim("typ", "Bearer")
                .claim("realm_access", realmAccess)
                .claim("preferred_username", user.getUsername())
                .claim("name", user.fullName())
                .claim("given_name", user.getFirstName())
                .claim("family_name", user.getLastName())
                .claim("email", user.getEmail())
                .claim("rma", user.getRma())
                .claim("organization_rma", user.getOrganizationRma())
                .claim("client_ids", user.getClientIds());
        // Каналы внешней системы-интегратора (сверка 25.09, G2): по ним обе службы пускают
        // учётку только в её разделы API. Нет claim'а — ограничения нет (служебная учётка).
        if (user.apiChannelList() != null) {
            builder.claim("api_channels", user.apiChannelList());
        }
        var claims = builder.build();

        try {
            var jwk = keys.active();
            var header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                    .type(JOSEObjectType.JWT)
                    .keyID(jwk.getKeyID())
                    .build();
            SignedJWT jwt = new SignedJWT(header, claims);
            jwt.sign(new RSASSASigner(jwk.toPrivateKey()));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось подписать токен доступа", e);
        }
    }
}
