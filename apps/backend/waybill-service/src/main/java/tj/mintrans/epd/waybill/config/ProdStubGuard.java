package tj.mintrans.epd.waybill.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * Прод-страховка (23.10.2026, безопасные дефолты): если активен Spring-профиль
 * {@code prod} (см. {@code SPRING_PROFILES_ACTIVE} в infra/docker-compose.prod.yml),
 * заглушка подписи титулов недопустима — приложение должно упасть при старте, а не
 * тихо выдавать SHA-256 вместо юридически значимой ЭП (Закон РТ № 1965).
 *
 * <p>До этой правки {@link StubTitleSigner} включался по {@code matchIfMissing=true} —
 * забытая переменная окружения означала запуск заглушки без единого предупреждения,
 * видимого кому-либо кроме журнала. Теперь режим обязателен к явному выбору
 * ({@code epd.signing.mode}), а для профиля prod дополнительно проверяется здесь.</p>
 */
@Configuration
public class ProdStubGuard {

    @Value("${epd.signing.mode:stub}")
    private String signingMode;

    private final Environment environment;

    public ProdStubGuard(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    void failFastOnStubInProd() {
        if (environment.acceptsProfiles(Profiles.of("prod")) && "stub".equals(signingMode)) {
            throw new IllegalStateException(
                    "Профиль prod активен, а epd.signing.mode=stub (SIGNING_MODE не задан или "
                    + "равен stub): подпись титулов DEV-заглушкой в проде запрещена. "
                    + "Задайте SIGNING_MODE=cades и SIGNING_CRYPTO_URL (адрес Crypto Service УЦ РТ) "
                    + "в infra/.env.");
        }
    }
}
