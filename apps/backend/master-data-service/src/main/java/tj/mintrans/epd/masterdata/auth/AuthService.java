package tj.mintrans.epd.masterdata.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tj.mintrans.epd.masterdata.domain.AppUser;
import tj.mintrans.epd.masterdata.domain.AuthRefreshToken;
import tj.mintrans.epd.masterdata.repository.AppUserRepository;
import tj.mintrans.epd.masterdata.repository.AuthRefreshTokenRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.UUID;

/**
 * Вход в платформу: проверка пароля, выпуск и обновление токенов, выход.
 *
 * <p>Заменяет Keycloak (решение владельца 23.09.2026). Состав выдаваемого токена не изменился,
 * поэтому проверка прав во всех службах осталась прежней.</p>
 *
 * <p>Что сделано осознанно:</p>
 * <ul>
 *   <li>пароли — BCrypt, в базе только хеш;</li>
 *   <li>токен обновления хранится отпечатком: по таблице войти нельзя;</li>
 *   <li>ответ при неверном логине и при неверном пароле одинаков — иначе перебором можно
 *       выяснить, какие логины существуют;</li>
 *   <li>временный пароль не пускает в систему: сперва смена (раньше это делала страница
 *       Keycloak, теперь — страница платформы).</li>
 * </ul>
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Пароль не принимается короче этого — прежняя политика Keycloak. */
    public static final int MIN_PASSWORD_LENGTH = 12;

    private final AppUserRepository users;
    private final AuthRefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwords;
    private final TokenIssuer tokens;
    private final tj.mintrans.epd.masterdata.service.AuditService audit;
    private final int maxFailedAttempts;
    private final long lockMinutes;
    private final long refreshTtlSeconds;

    public AuthService(AppUserRepository users, AuthRefreshTokenRepository refreshTokens,
                       PasswordEncoder passwords, TokenIssuer tokens,
                       tj.mintrans.epd.masterdata.service.AuditService audit,
                       @Value("${epd.auth.max-failed-attempts:10}") int maxFailedAttempts,
                       @Value("${epd.auth.lock-minutes:15}") long lockMinutes,
                       @Value("${epd.auth.refresh-ttl-seconds:43200}") long refreshTtlSeconds) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.passwords = passwords;
        this.tokens = tokens;
        this.audit = audit;
        this.maxFailedAttempts = maxFailedAttempts;
        this.lockMinutes = lockMinutes;
        this.refreshTtlSeconds = refreshTtlSeconds;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    /** Хеш случайного пароля — для выравнивания времени ответа на несуществующий логин. */
    private final String dummyHash;

    /** Ответ точки выдачи токена — поля те же, что отдавал Keycloak. */
    public record Tokens(String accessToken, long expiresIn, String refreshToken,
                         long refreshExpiresIn, String tokenType) {
    }

    /** Требуется смена временного пароля: вход не даётся, но выдаётся одноразовый ключ смены. */
    public static class PasswordChangeRequired extends RuntimeException {
        private final String changeToken;

        PasswordChangeRequired(String changeToken) {
            super("Требуется смена пароля");
            this.changeToken = changeToken;
        }

        public String changeToken() {
            return changeToken;
        }
    }

    /**
     * Вход по логину и паролю.
     *
     * <p>{@code noRollbackFor}: отказ во входе сообщается исключением, но записи, сделанные до
     * него, обязаны сохраниться — счётчик неудачных попыток (иначе блокировка после N неудач
     * никогда не срабатывает) и ключ смены временного пароля (иначе страница смены отвечает
     * «ключ недействителен» и новый пользователь не может войти вообще). До исправления
     * 23.09.2026 любое из этих исключений откатывало транзакцию вместе с записями.</p>
     */
    @Transactional(noRollbackFor = {ResponseStatusException.class, PasswordChangeRequired.class})
    public Tokens login(String username, String password) {
        var user = users.findByUsername(username == null ? "" : username.trim()).orElse(null);
        if (user == null) {
            // Пароль всё равно проверяем настоящим BCrypt-хешем, чтобы время ответа не выдавало,
            // существует ли логин (заглушка неверного формата отвечала мгновенно).
            passwords.matches(password == null ? "" : password, dummyHash);
            throw invalidCredentials();
        }
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(OffsetDateTime.now())) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Учётная запись временно заблокирована из-за неудачных попыток входа. Повторите позже.");
        }
        if (!passwords.matches(password == null ? "" : password, user.getPasswordHash())) {
            registerFailure(user);
            throw invalidCredentials();
        }
        if (!user.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Учётная запись отключена");
        }

        user.setFailedAttempts(0);
        user.setLockedUntil(null);

        if (user.isMustChangePassword()) {
            // Пароль верен, но временный: в систему не пускаем, выдаём ключ для страницы смены.
            users.save(user);
            throw new PasswordChangeRequired(issueRefresh(user, true));
        }

        user.setLastLoginAt(OffsetDateTime.now());
        users.save(user);
        // Вход в журнал аудита: раньше эти записи приходили из событий Keycloak отдельной
        // выгрузкой, теперь их пишет сама платформа в момент входа.
        audit.recordAs(user.getUsername(), user.getOrganizationRma(), "LOGIN", "AUTH",
                user.getUsername(), null, null, null, null);
        return issueTokens(user);
    }

    @Transactional
    public Tokens refresh(String refreshToken) {
        var stored = refreshTokens.findByTokenHash(hash(refreshToken)).orElse(null);
        if (stored == null || !stored.isUsable()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Сессия истекла, войдите заново");
        }
        var user = users.findById(stored.getUserId()).orElse(null);
        // mustChangePassword: ключ смены временного пароля лежит в той же таблице, что и токены
        // обновления. Без этой проверки его можно было предъявить сюда и получить полноценный
        // вход, так и не сменив временный пароль.
        if (user == null || !user.isEnabled() || user.isMustChangePassword()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Сессия истекла, войдите заново");
        }
        // Одноразовость: предъявленный токен гасим и выдаём новую пару.
        stored.setRevoked(true);
        refreshTokens.save(stored);
        return issueTokens(user);
    }

    @Transactional
    public void logout(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        refreshTokens.findByTokenHash(hash(refreshToken)).ifPresent(t -> {
            t.setRevoked(true);
            refreshTokens.save(t);
        });
    }

    /**
     * Смена пароля. Либо по ключу смены (первый вход с временным паролем), либо для уже
     * вошедшего пользователя — тогда обязателен текущий пароль.
     */
    @Transactional
    public void changePassword(UUID userId, String changeToken, String currentPassword, String newPassword) {
        AppUser user;
        if (changeToken != null && !changeToken.isBlank()) {
            var stored = refreshTokens.findByTokenHash(hash(changeToken))
                    .filter(AuthRefreshToken::isUsable)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                            "Ключ смены пароля недействителен, войдите заново"));
            user = users.findById(stored.getUserId()).orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Учётная запись не найдена"));
            // Ключ смены годится только пока пароль временный. Иначе обычный токен обновления
            // (та же таблица) позволял бы сменить пароль, не зная текущего.
            if (!user.isMustChangePassword()) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Ключ смены пароля недействителен, войдите заново");
            }
        } else {
            if (userId == null) {
                // Ни ключа смены, ни действующего входа (например, страницу смены открыли
                // напрямую или ключ уже израсходован) — просим войти заново, а не падаем в 500.
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Ключ смены пароля недействителен, войдите заново");
            }
            user = users.findById(userId).orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Учётная запись не найдена"));
            if (!passwords.matches(currentPassword == null ? "" : currentPassword, user.getPasswordHash())) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Текущий пароль указан неверно");
            }
        }
        assertPasswordPolicy(newPassword, user.getUsername());
        if (passwords.matches(newPassword, user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Новый пароль совпадает с текущим");
        }
        user.setPasswordHash(passwords.encode(newPassword));
        user.setMustChangePassword(false);
        user.setFailedAttempts(0);
        user.setLockedUntil(null);
        users.save(user);
        // Смена пароля гасит все сессии: украденный токен обновления перестаёт работать.
        refreshTokens.revokeAllForUser(user.getId());
        log.info("Пароль изменён: {}", user.getUsername());
    }

    /** Политика пароля — прежняя, из настроек Keycloak: длина и несовпадение с логином. */
    public static void assertPasswordPolicy(String password, String username) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Пароль должен быть не короче " + MIN_PASSWORD_LENGTH + " символов");
        }
        if (username != null && password.equalsIgnoreCase(username)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Пароль не должен совпадать с логином");
        }
    }

    private Tokens issueTokens(AppUser user) {
        String access = tokens.accessToken(user);
        String refresh = issueRefresh(user, false);
        return new Tokens(access, tokens.accessTtlSeconds(), refresh, refreshTtlSeconds, "Bearer");
    }

    private String issueRefresh(AppUser user, boolean shortLived) {
        byte[] raw = new byte[48];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        var entity = new AuthRefreshToken();
        entity.setUserId(user.getId());
        entity.setTokenHash(hash(token));
        entity.setExpiresAt(OffsetDateTime.now().plusSeconds(shortLived ? 900 : refreshTtlSeconds));
        refreshTokens.save(entity);
        return token;
    }

    private void registerFailure(AppUser user) {
        int attempts = user.getFailedAttempts() + 1;
        user.setFailedAttempts(attempts);
        if (attempts >= maxFailedAttempts) {
            user.setLockedUntil(OffsetDateTime.now().plusMinutes(lockMinutes));
            user.setFailedAttempts(0);
            log.warn("Учётная запись {} заблокирована на {} мин: превышено число неудачных попыток входа",
                    user.getUsername(), lockMinutes);
        }
        users.save(user);
    }

    private static ResponseStatusException invalidCredentials() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Неверный логин или пароль");
    }

    /** Отпечаток токена: в базе хранится он, а не сам токен. */
    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((token == null ? "" : token).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось вычислить отпечаток токена", e);
        }
    }
}
