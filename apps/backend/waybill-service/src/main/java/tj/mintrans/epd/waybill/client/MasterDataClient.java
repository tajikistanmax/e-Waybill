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

    public MasterDataClient(@Value("${epd.master-data.base-url}") String baseUrl) {
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestInterceptor(MasterDataClient::forwardBearerToken)
                .build();
    }

    /** Проброс Authorization входящего HTTP-запроса в вызов master-data (token relay). */
    private static ClientHttpResponse forwardBearerToken(HttpRequest request, byte[] body,
                                                         ClientHttpRequestExecution execution) throws IOException {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            String authorization = attrs.getRequest().getHeader(HttpHeaders.AUTHORIZATION);
            if (authorization != null && !request.getHeaders().containsKey(HttpHeaders.AUTHORIZATION)) {
                request.getHeaders().set(HttpHeaders.AUTHORIZATION, authorization);
            }
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

    public void updateVehicleOdometer(String vehicleId, int odometer) {
        client.patch()
                .uri("/api/v1/vehicles/{id}/odometer", vehicleId)
                .body(Map.of("odometer", odometer))
                .retrieve()
                .toBodilessEntity();
    }

    private Optional<Map<String, Object>> first(String uri, Object... vars) {
        List<Map<String, Object>> list = client.get().uri(uri, vars).retrieve().body(LIST_OF_MAPS);
        return list == null || list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }
}
