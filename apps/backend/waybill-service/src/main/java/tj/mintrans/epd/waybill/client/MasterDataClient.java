package tj.mintrans.epd.waybill.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Клиент мастер-данных (единая платформа Минтранса; на этапе 1а — master-data-service).
 * Возвращает «сырые» Map — они же становятся снимками (snapshot) в документе.
 * Bearer-токен текущего запроса пробрасывается дальше (master-data требует JWT).
 */
@Component
public class MasterDataClient {

    private final RestClient client;
    private final ServiceTokenProvider serviceToken;

    public MasterDataClient(@Value("${epd.master-data.base-url}") String baseUrl,
                            ServiceTokenProvider serviceToken) {
        this.serviceToken = serviceToken;
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestInterceptor(this::authorize)
                .build();
    }

    /**
     * Аутентификация вызова master-data: пробрасывается Bearer текущего пользователя (token relay);
     * если запрос без токена (агрегатор ЧУРА/НЕРУ, планировщик) — берётся сервисный client-credentials
     * токен (роль API_INTEGRATOR), чтобы master-data не приходилось держать GET открытым анонимно.
     */
    private ClientHttpResponse authorize(HttpRequest request, byte[] body,
                                         ClientHttpRequestExecution execution) throws IOException {
        if (!request.getHeaders().containsKey(HttpHeaders.AUTHORIZATION)) {
            String userAuthorization = null;
            if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
                userAuthorization = attrs.getRequest().getHeader(HttpHeaders.AUTHORIZATION);
            }
            String authorization = userAuthorization != null
                    ? userAuthorization
                    : "Bearer " + serviceToken.bearer();
            request.getHeaders().set(HttpHeaders.AUTHORIZATION, authorization);
        }
        return execution.execute(request, body);
    }

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_OF_MAPS =
            new ParameterizedTypeReference<>() {};

    public Optional<Map<String, Object>> findOrganization(String rma) {
        return first("/api/v1/organizations?rma={rma}", rma);
    }

    public Optional<Map<String, Object>> findDriver(String rma) {
        return first("/api/v1/drivers?rma={rma}", rma);
    }

    public Optional<Map<String, Object>> findVehicle(String registrationNumber) {
        return first("/api/v1/vehicles?registrationNumber={n}", registrationNumber);
    }

    public Optional<Map<String, Object>> findEmployee(String rma) {
        return first("/api/v1/employees?rma={rma}", rma);
    }

    /** Онлайн-проверка дозвола E-PERMIT через единую платформу (404 → empty). */
    public Optional<Map<String, Object>> findPermit(String permitNumber) {
        try {
            Map<String, Object> permit = client.get()
                    .uri("/api/v1/sync/permit/{number}", permitNumber)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});
            return Optional.ofNullable(permit);
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }

    public void updateVehicleOdometer(String vehicleId, int odometer) {
        client.patch()
                .uri("/api/v1/vehicles/{id}/odometer", vehicleId)
                .body(Map.of("odometer", odometer))
                .retrieve()
                .toBodilessEntity();
    }

    // ------------------------------------------------------- движок политик

    private static final ParameterizedTypeReference<Map<String, String>> STRING_MAP =
            new ParameterizedTypeReference<>() {};

    /**
     * Эффективные правила (движок политик master-data) для (организация, тип ПЛ).
     * При недоступности движка возвращает пустую карту — вызывающий код применяет безопасный фолбэк.
     */
    public Map<String, String> effectivePolicies(String organizationRma, String waybillType) {
        try {
            Map<String, String> map = client.get()
                    .uri("/api/v1/policies/effective?organizationRma={o}&waybillType={t}",
                            organizationRma, waybillType)
                    .retrieve()
                    .body(STRING_MAP);
            return map == null ? Map.of() : map;
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    // ------------------------------------------------------- справочники нормирования

    public List<Map<String, Object>> listFuelNorms() {
        return list("/api/v1/dictionaries/fuel-norms");
    }

    public List<Map<String, Object>> listCoefficients() {
        return list("/api/v1/dictionaries/coefficients");
    }

    public List<Map<String, Object>> listTariffs() {
        return list("/api/v1/dictionaries/tariffs");
    }

    private List<Map<String, Object>> list(String uri, Object... vars) {
        List<Map<String, Object>> list = client.get().uri(uri, vars).retrieve().body(LIST_OF_MAPS);
        return list == null ? List.of() : list;
    }

    private Optional<Map<String, Object>> first(String uri, Object... vars) {
        List<Map<String, Object>> list = client.get().uri(uri, vars).retrieve().body(LIST_OF_MAPS);
        return list == null || list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }
}
