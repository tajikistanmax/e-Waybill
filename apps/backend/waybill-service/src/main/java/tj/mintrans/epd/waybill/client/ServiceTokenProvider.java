package tj.mintrans.epd.waybill.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Сервисный токен (OAuth2 client-credentials) для межсервисных вызовов master-data,
 * когда пользовательского токена в запросе нет (агрегатор ЧУРА/НЕРУ, планировщик).
 * Клиент Keycloak epd-aggregator (роль API_INTEGRATOR). Токен кэшируется до истечения.
 */
@Component
public class ServiceTokenProvider {

    private final RestClient tokenClient;
    private final String clientId;
    private final String clientSecret;
    private volatile String cachedToken;
    private volatile long expiresAtMs;

    public ServiceTokenProvider(@Value("${epd.service-account.token-uri}") String tokenUri,
                                @Value("${epd.service-account.client-id}") String clientId,
                                @Value("${epd.service-account.client-secret}") String clientSecret) {
        this.tokenClient = RestClient.create(tokenUri);
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    /** Действующий сервисный access-token (из кэша либо новый). */
    public synchronized String bearer() {
        long now = System.currentTimeMillis();
        if (cachedToken != null && now < expiresAtMs - 10_000) {
            return cachedToken;
        }
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        Map<String, Object> resp = tokenClient.post()
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {});
        if (resp == null || resp.get("access_token") == null) {
            throw new IllegalStateException("Не удалось получить сервисный токен от Keycloak");
        }
        cachedToken = String.valueOf(resp.get("access_token"));
        int expiresIn = resp.get("expires_in") instanceof Number n ? n.intValue() : 60;
        expiresAtMs = now + expiresIn * 1000L;
        return cachedToken;
    }
}
