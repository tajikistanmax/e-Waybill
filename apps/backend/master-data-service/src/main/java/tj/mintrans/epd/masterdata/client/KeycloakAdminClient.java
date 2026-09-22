package tj.mintrans.epd.masterdata.client;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Тонкий клиент Keycloak Admin API (realm {@code epd}) для провижининга логинов
 * сотрудников перевозчика: создать пользователя, задать временный пароль, назначить
 * одну realm-роль, включить/выключить, удалить.
 *
 * <p>Аутентификация — client-credentials сервисного аккаунта {@code epd-provisioner}
 * (realm-management: manage-users). Токен кэшируется до истечения срока.</p>
 *
 * <p>{@code epd.keycloak.enabled=false} → любой вызов 503 (провижининг недоступен —
 * фронт прячет экран «Доступы»).</p>
 */
@Component
public class KeycloakAdminClient {

    /** Учётка сотрудника перевозчика в realm epd (проекция для UI — без секретов). */
    public record OrgUser(String id, String username, String firstName, String lastName,
                          boolean enabled, String rma, String organizationRma, List<String> roles) {
    }

    private final boolean enabled;
    private final String realm;
    private final String clientId;
    private final String clientSecret;
    private final RestClient http;

    private volatile String cachedToken;
    private volatile long tokenExpiresAt;

    public KeycloakAdminClient(
            @Value("${epd.keycloak.enabled:true}") boolean enabled,
            @Value("${epd.keycloak.base-url:http://keycloak:8180}") String baseUrl,
            @Value("${epd.keycloak.realm:epd}") String realm,
            @Value("${epd.keycloak.client-id:epd-provisioner}") String clientId,
            @Value("${epd.keycloak.client-secret:}") String clientSecret) {
        this.enabled = enabled;
        this.realm = realm;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.http = RestClient.builder().baseUrl(baseUrl).build();
    }

    public boolean isEnabled() {
        return enabled;
    }

    private void assertEnabled() {
        if (!enabled) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "Провижининг логинов отключён (epd.keycloak.enabled=false)");
        }
    }

    // ------------------------------------------------------------------ токен

    private synchronized String token() {
        long now = System.currentTimeMillis();
        if (cachedToken != null && tokenExpiresAt > now + 5_000) {
            return cachedToken;
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        JsonNode body = http.post()
                .uri("/realms/{realm}/protocol/openid-connect/token", realm)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(JsonNode.class);
        if (body == null || body.get("access_token") == null) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_GATEWAY,
                    "Keycloak не выдал admin-токен");
        }
        cachedToken = body.get("access_token").asText();
        long ttl = body.has("expires_in") ? body.get("expires_in").asLong() : 60L;
        tokenExpiresAt = now + ttl * 1000;
        return cachedToken;
    }

    private RestClient.RequestHeadersSpec<?> get(String uri, Object... vars) {
        return http.get().uri(uri, vars).header("Authorization", "Bearer " + token());
    }

    // ------------------------------------------------------------------ пользователи

    /** Пользователи realm с атрибутом organizationRma из набора (по одному запросу на РМА). */
    public List<OrgUser> listByOrganizations(Iterable<String> organizationRmas) {
        assertEnabled();
        List<OrgUser> out = new ArrayList<>();
        for (String rma : organizationRmas) {
            JsonNode arr = get("/admin/realms/{realm}/users?q=organizationRma:{rma}&max=500", realm, rma)
                    .retrieve().body(JsonNode.class);
            if (arr != null && arr.isArray()) {
                arr.forEach(n -> {
                    OrgUser u = toOrgUser(n);
                    out.add(new OrgUser(u.id(), u.username(), u.firstName(), u.lastName(), u.enabled(),
                            u.rma(), u.organizationRma(), realmRoleNames(u.id())));
                });
            }
        }
        return out;
    }

    public OrgUser getUser(String userId) {
        assertEnabled();
        JsonNode n = get("/admin/realms/{realm}/users/{id}", realm, userId).retrieve().body(JsonNode.class);
        if (n == null) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Пользователь не найден");
        }
        OrgUser u = toOrgUser(n);
        return new OrgUser(u.id(), u.username(), u.firstName(), u.lastName(), u.enabled(),
                u.rma(), u.organizationRma(), realmRoleNames(userId));
    }

    /**
     * Создаёт пользователя + временный пароль + одну realm-роль. Возвращает id созданного.
     * username чаще всего — телефон; дубль username → 409 от Keycloak (пробрасываем).
     */
    public String createUser(String username, String firstName, String lastName, String email,
                             String rma, String organizationRma, String temporaryPassword) {
        return createUser(username, firstName, lastName, email, rma, organizationRma, temporaryPassword, null);
    }

    /**
     * То же с атрибутом {@code clientIds} — контрагенты внешнего пользователя кабинета накладных
     * (MIGRATION.md 1.1/3.11: claim {@code client_ids} задаёт, чьи накладные он видит).
     */
    public String createUser(String username, String firstName, String lastName, String email,
                             String rma, String organizationRma, String temporaryPassword,
                             List<String> clientIds) {
        assertEnabled();
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("username", username);
        payload.put("enabled", true);
        if (firstName != null && !firstName.isBlank()) payload.put("firstName", firstName);
        if (lastName != null && !lastName.isBlank()) payload.put("lastName", lastName);
        if (email != null && !email.isBlank()) {
            payload.put("email", email);
            payload.put("emailVerified", true);
        }
        Map<String, Object> attrs = new java.util.HashMap<>();
        if (rma != null && !rma.isBlank()) attrs.put("rma", List.of(rma));
        if (organizationRma != null && !organizationRma.isBlank()) attrs.put("organizationRma", List.of(organizationRma));
        if (clientIds != null && !clientIds.isEmpty()) attrs.put("clientIds", List.of(String.join(",", clientIds)));
        payload.put("attributes", attrs);
        payload.put("credentials", List.of(Map.of(
                "type", "password", "value", temporaryPassword, "temporary", true)));

        var resp = http.post()
                .uri("/admin/realms/{realm}/users", realm)
                .header("Authorization", "Bearer " + token())
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .toBodilessEntity();
        URI location = resp.getHeaders().getLocation();
        if (location == null) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_GATEWAY,
                    "Keycloak не вернул id созданного пользователя");
        }
        String path = location.getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /** Назначает realm-роль (снимая ранее назначенные из whitelist — одна роль на пользователя). */
    public void setSingleRealmRole(String userId, String roleName, Iterable<String> revocableRoles) {
        assertEnabled();
        // снять ранее выданные роли из белого списка
        List<Map<String, Object>> toRemove = new ArrayList<>();
        for (String r : revocableRoles) {
            if (!r.equals(roleName)) {
                JsonNode role = realmRole(r);
                if (role != null && has(userId, r)) {
                    toRemove.add(Map.of("id", role.get("id").asText(), "name", r));
                }
            }
        }
        if (!toRemove.isEmpty()) {
            http.method(org.springframework.http.HttpMethod.DELETE)
                    .uri("/admin/realms/{realm}/users/{id}/role-mappings/realm", realm, userId)
                    .header("Authorization", "Bearer " + token())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(toRemove)
                    .retrieve().toBodilessEntity();
        }
        JsonNode role = realmRole(roleName);
        if (role == null) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                    "Роль %s не найдена в realm".formatted(roleName));
        }
        http.post()
                .uri("/admin/realms/{realm}/users/{id}/role-mappings/realm", realm, userId)
                .header("Authorization", "Bearer " + token())
                .contentType(MediaType.APPLICATION_JSON)
                .body(List.of(Map.of("id", role.get("id").asText(), "name", roleName)))
                .retrieve().toBodilessEntity();
    }

    public void setEnabled(String userId, boolean enabledFlag) {
        assertEnabled();
        http.put()
                .uri("/admin/realms/{realm}/users/{id}", realm, userId)
                .header("Authorization", "Bearer " + token())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("enabled", enabledFlag))
                .retrieve().toBodilessEntity();
    }

    public void resetPassword(String userId, String temporaryPassword) {
        assertEnabled();
        http.put()
                .uri("/admin/realms/{realm}/users/{id}/reset-password", realm, userId)
                .header("Authorization", "Bearer " + token())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("type", "password", "value", temporaryPassword, "temporary", true))
                .retrieve().toBodilessEntity();
    }

    public void deleteUser(String userId) {
        assertEnabled();
        http.delete()
                .uri("/admin/realms/{realm}/users/{id}", realm, userId)
                .header("Authorization", "Bearer " + token())
                .retrieve().toBodilessEntity();
    }

    // ------------------------------------------------------------------ события входа (ИБ-13.6.1)

    /** Событие аутентификации Keycloak (LOGIN/LOGIN_ERROR/LOGOUT) — для приёма в audit_log. */
    public record AuthEvent(String type, String username, String clientId, String ipAddress,
                            String error, long timeMillis) {
    }

    /**
     * Последние события входа/выхода (без серверной фильтрации по дате — формат dateFrom
     * в разных версиях Keycloak различается; вместо этого забираем последние {@code max}
     * и фильтруем по времени на стороне вызывающего). Требует роль {@code view-events}
     * у сервисного аккаунта (см. epd-realm.json).
     */
    public List<AuthEvent> recentAuthEvents(int max) {
        assertEnabled();
        JsonNode arr = get("/admin/realms/{realm}/events?max={max}", realm, max)
                .retrieve().body(JsonNode.class);
        List<AuthEvent> out = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            arr.forEach(n -> {
                JsonNode details = n.get("details");
                String username = details != null && details.hasNonNull("username")
                        ? details.get("username").asText() : text(n, "userId");
                out.add(new AuthEvent(
                        text(n, "type"), username, text(n, "clientId"), text(n, "ipAddress"),
                        text(n, "error"), n.path("time").asLong(0)));
            });
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode realmRole(String name) {
        return get("/admin/realms/{realm}/roles/{name}", realm, name).retrieve().body(JsonNode.class);
    }

    private boolean has(String userId, String roleName) {
        return realmRoleNames(userId).contains(roleName);
    }

    private List<String> realmRoleNames(String userId) {
        JsonNode arr = get("/admin/realms/{realm}/users/{id}/role-mappings/realm", realm, userId)
                .retrieve().body(JsonNode.class);
        List<String> names = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            arr.forEach(n -> names.add(n.get("name").asText()));
        }
        return names;
    }

    private static OrgUser toOrgUser(JsonNode n) {
        JsonNode attrs = n.get("attributes");
        return new OrgUser(
                text(n, "id"),
                text(n, "username"),
                text(n, "firstName"),
                text(n, "lastName"),
                n.path("enabled").asBoolean(false),
                attr(attrs, "rma"),
                attr(attrs, "organizationRma"),
                List.of());
    }

    private static String text(JsonNode n, String field) {
        return n.hasNonNull(field) ? n.get(field).asText() : null;
    }

    private static String attr(JsonNode attrs, String key) {
        if (attrs == null || !attrs.has(key) || !attrs.get(key).isArray() || attrs.get(key).isEmpty()) {
            return null;
        }
        return attrs.get(key).get(0).asText();
    }
}
