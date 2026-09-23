package tj.mintrans.epd.waybill.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Служебный токен для межсервисных вызовов master-data, когда пользовательского токена в
 * запросе нет (агрегатор ЧУРА/НЕРУ, планировщик, перенос одометра при закрытии листа).
 *
 * <p>До 23.09.2026 это был client-credentials клиента Keycloak. После отказа от Keycloak
 * (решение владельца) служба входит служебной учётной записью платформы с ролью
 * {@code API_INTEGRATOR} — тем же способом, что и человек, только логин и пароль берутся из
 * окружения. Токен кэшируется до истечения.</p>
 */
@Component
public class ServiceTokenProvider {

    private final RestClient tokenClient;
    private final String username;
    private final String password;
    private volatile String cachedToken;
    private volatile long expiresAtMs;

    public ServiceTokenProvider(
            @Value("${epd.service-account.token-uri:http://master-data:8081/api/v1/auth/token}") String tokenUri,
            @Value("${epd.service-account.username:}") String username,
            @Value("${epd.service-account.password:}") String password) {
        this.tokenClient = RestClient.create(tokenUri);
        this.username = username;
        this.password = password;
    }

    /** Действующий служебный токен доступа (из кэша либо новый). */
    public synchronized String bearer() {
        long now = System.currentTimeMillis();
        if (cachedToken != null && now < expiresAtMs - 10_000) {
            return cachedToken;
        }
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new IllegalStateException("Служебная учётная запись не настроена: задайте "
                    + "SERVICE_ACCOUNT_USERNAME и SERVICE_ACCOUNT_PASSWORD");
        }
        Map<String, Object> resp = tokenClient.post()
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("username", username, "password", password))
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {});
        if (resp == null || resp.get("access_token") == null) {
            throw new IllegalStateException("Не удалось получить служебный токен платформы");
        }
        cachedToken = String.valueOf(resp.get("access_token"));
        int expiresIn = resp.get("expires_in") instanceof Number n ? n.intValue() : 60;
        expiresAtMs = now + expiresIn * 1000L;
        return cachedToken;
    }
}
