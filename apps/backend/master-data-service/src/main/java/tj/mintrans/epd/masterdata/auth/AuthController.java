package tj.mintrans.epd.masterdata.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Вход в платформу. Заменяет точки Keycloak (решение владельца 23.09.2026).
 *
 * <p>Пути открыты без токена — это и есть вход; всё остальное API закрыто, как и раньше.</p>
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService auth;
    private final SigningKeys keys;

    public AuthController(AuthService auth, SigningKeys keys) {
        this.auth = auth;
        this.keys = keys;
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record RefreshRequest(@NotBlank String refreshToken) {
    }

    public record LogoutRequest(String refreshToken) {
    }

    public record PasswordRequest(String changeToken, String currentPassword, @NotBlank String newPassword) {
    }

    /**
     * Выдача токена по логину и паролю. Ответ повторяет прежний формат, чтобы клиент не
     * пришлось переписывать целиком.
     *
     * <p>Если пароль временный — 428 с одноразовым ключом: вход не даётся, интерфейс ведёт
     * на страницу смены пароля (раньше это делала страница Keycloak на чужом адресе).</p>
     */
    @PostMapping("/token")
    public ResponseEntity<Map<String, Object>> token(@Valid @RequestBody LoginRequest req) {
        try {
            return ResponseEntity.ok(body(auth.login(req.username(), req.password())));
        } catch (AuthService.PasswordChangeRequired e) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("error", "password_change_required");
            out.put("changeToken", e.changeToken());
            out.put("message", "Пароль временный. Задайте постоянный пароль для входа.");
            return ResponseEntity.status(HttpStatus.PRECONDITION_REQUIRED).body(out);
        }
    }

    @PostMapping("/refresh")
    public Map<String, Object> refresh(@Valid @RequestBody RefreshRequest req) {
        return body(auth.refresh(req.refreshToken()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestBody(required = false) LogoutRequest req) {
        auth.logout(req == null ? null : req.refreshToken());
        return ResponseEntity.noContent().build();
    }

    /** Смена пароля: по одноразовому ключу (первый вход) либо своего, уже войдя в систему. */
    @PostMapping("/password")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody PasswordRequest req) {
        auth.changePassword(currentUserId(), req.changeToken(), req.currentPassword(), req.newPassword());
        return ResponseEntity.noContent().build();
    }

    /** Открытые ключи для проверки подписи — их читает служба путевых листов. */
    @GetMapping("/jwks")
    public Map<String, Object> jwks() {
        return keys.publicSet().toJSONObject();
    }

    private static Map<String, Object> body(AuthService.Tokens t) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("access_token", t.accessToken());
        out.put("expires_in", t.expiresIn());
        out.put("refresh_token", t.refreshToken());
        out.put("refresh_expires_in", t.refreshExpiresIn());
        out.put("token_type", t.tokenType());
        return out;
    }

    /** Идентификатор вошедшего пользователя (subject токена), либо null для анонима. */
    private static UUID currentUserId() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt) {
            try {
                return UUID.fromString(jwt.getToken().getSubject());
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }
}
