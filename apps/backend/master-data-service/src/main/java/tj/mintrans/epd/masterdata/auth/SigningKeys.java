package tj.mintrans.epd.masterdata.auth;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tj.mintrans.epd.masterdata.domain.AuthSigningKey;
import tj.mintrans.epd.masterdata.repository.AuthSigningKeyRepository;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Ключ подписи токенов платформы.
 *
 * <p>Пара RSA хранится в базе ({@code auth_signing_key}) и создаётся один раз при первом
 * запуске. Держать её в памяти нельзя: перезапуск службы менял бы подпись, все выданные
 * токены разом становились недействительными и всех работающих выбрасывало из системы.</p>
 *
 * <p>Наружу отдаётся только открытая часть — набор ключей, по которому служба путевых листов
 * проверяет подпись, не зная секрета.</p>
 */
@Component
public class SigningKeys {

    private static final Logger log = LoggerFactory.getLogger(SigningKeys.class);
    private static final int KEY_SIZE = 2048;

    private final AuthSigningKeyRepository keys;

    private volatile RSAKey active;
    private volatile JWKSet publicSet;

    public SigningKeys(AuthSigningKeyRepository keys) {
        this.keys = keys;
    }

    @PostConstruct
    @Transactional
    public void init() {
        var stored = keys.findFirstByActiveTrueOrderByCreatedAtDesc().orElseGet(this::generateAndStore);
        this.active = toRsaKey(stored);
        reloadPublicSet();
        log.info("Ключ подписи токенов: kid={} (всего ключей в базе: {})", stored.getId(), keys.count());
    }

    /** Действующий ключ — им подписываются новые токены. */
    public RSAKey active() {
        return active;
    }

    /** Открытый набор ключей для проверки подписи (в т.ч. службой путевых листов). */
    public JWKSet publicSet() {
        return publicSet;
    }

    private void reloadPublicSet() {
        List<com.nimbusds.jose.jwk.JWK> all = new ArrayList<>();
        for (AuthSigningKey k : keys.findAllByOrderByCreatedAtDesc()) {
            all.add(toRsaKey(k).toPublicJWK());
        }
        this.publicSet = new JWKSet(all);
    }

    private AuthSigningKey generateAndStore() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(KEY_SIZE);
            KeyPair pair = gen.generateKeyPair();
            var entity = new AuthSigningKey();
            entity.setId("epd-auth-" + System.currentTimeMillis());
            entity.setPrivateKey(Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
            entity.setPublicKey(Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
            entity.setActive(true);
            log.info("Ключ подписи токенов не найден — создан новый: kid={}", entity.getId());
            return keys.save(entity);
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось создать ключ подписи токенов", e);
        }
    }

    private static RSAKey toRsaKey(AuthSigningKey stored) {
        try {
            KeyFactory factory = KeyFactory.getInstance("RSA");
            var pub = (RSAPublicKey) factory.generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(stored.getPublicKey())));
            var priv = (RSAPrivateKey) factory.generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.getDecoder().decode(stored.getPrivateKey())));
            return new RSAKey.Builder(pub).privateKey(priv).keyID(stored.getId()).build();
        } catch (Exception e) {
            throw new IllegalStateException("Ключ подписи в базе повреждён: " + stored.getId(), e);
        }
    }
}
