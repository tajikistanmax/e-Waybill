package tj.mintrans.epd.masterdata.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * Прод-страховка (23.10.2026, безопасные дефолты): если активен Spring-профиль
 * {@code prod} (см. {@code SPRING_PROFILES_ACTIVE} в infra/docker-compose.prod.yml),
 * заглушка единой платформы Минтранса недопустима — приложение должно упасть при
 * старте, а не тихо имитировать налоговую/ГАИ в проде.
 *
 * <p>До этой правки {@code StubUnifiedPlatformClient} включался по
 * {@code matchIfMissing=true} — забытая переменная окружения означала запуск заглушки
 * без единого предупреждения, видимого кому-либо кроме журнала. Теперь режим обязателен
 * к явному выбору ({@code epd.unified-platform.mode}), а для профиля prod дополнительно
 * проверяется здесь.</p>
 */
@Configuration
public class ProdStubGuard {

    @Value("${epd.unified-platform.mode:stub}")
    private String unifiedPlatformMode;

    private final Environment environment;

    public ProdStubGuard(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    void failFastOnStubInProd() {
        if (environment.acceptsProfiles(Profiles.of("prod")) && "stub".equals(unifiedPlatformMode)) {
            throw new IllegalStateException(
                    "Профиль prod активен, а epd.unified-platform.mode=stub (UNIFIED_PLATFORM_MODE "
                    + "не задан или равен stub): имитация налоговой/ГАИ в проде запрещена. "
                    + "Задайте UNIFIED_PLATFORM_MODE=http и UNIFIED_PLATFORM_URL (адрес единой "
                    + "платформы Минтранса) в infra/.env.");
        }
    }
}
