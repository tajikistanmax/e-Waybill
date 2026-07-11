package tj.mintrans.epd.masterdata.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Optional;

/**
 * Продакшн-клиент единой платформы транспорта Минтранса (UNIFIED_PLATFORM_MODE=http).
 * Ожидаемый контракт API единой платформы:
 *   GET {base-url}/api/v1/subjects/{inn}            → Subject (данные налоговой)
 *   GET {base-url}/api/v1/vehicles/{regNumber}      → VehicleInfo (база ГАИ)
 *   GET {base-url}/api/v1/driver-licenses/{inn}     → DriverLicense (ГАИ + Минздрав)
 * 404 → Optional.empty(). Авторизация — сервисный токен (TODO: client-credentials
 * при подключении к реальной единой платформе).
 */
@Component
@ConditionalOnProperty(name = "epd.unified-platform.mode", havingValue = "http")
public class HttpUnifiedPlatformClient implements UnifiedPlatformClient {

    private final RestClient client;

    public HttpUnifiedPlatformClient(@Value("${epd.unified-platform.base-url}") String baseUrl) {
        // Явные таймауты: без них зависший налоговая/ГАИ/E-PERMIT удерживал бы поток Tomcat
        // бесконечно, и параллельные sync/permit-запросы исчерпали бы пул (отказ в обслуживании).
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(5000);
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Override
    public Optional<Subject> findSubject(String inn) {
        return get("/api/v1/subjects/{inn}", Subject.class, inn);
    }

    @Override
    public Optional<VehicleInfo> findVehicle(String registrationNumber) {
        return get("/api/v1/vehicles/{reg}", VehicleInfo.class, registrationNumber);
    }

    @Override
    public Optional<DriverLicense> findDriverLicense(String inn) {
        return get("/api/v1/driver-licenses/{inn}", DriverLicense.class, inn);
    }

    @Override
    public Optional<PermitInfo> findPermit(String permitNumber) {
        return get("/api/v1/permits/{number}", PermitInfo.class, permitNumber);
    }

    private <T> Optional<T> get(String uri, Class<T> type, Object... vars) {
        try {
            return Optional.ofNullable(client.get().uri(uri, vars).retrieve().body(type));
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }
}
