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
    /** Подпись записи в приложении-аутентификаторе. */
    private final String totpIssuer;

    /** Срок ключа смены временного пароля и ключа второго шага входа. */
    private static final long SHORT_KEY_SECONDS = 900;

    public AuthService(AppUserRepository users, AuthRefreshTokenRepository refreshTokens,
                       PasswordEncoder passwords, TokenIssuer tokens,
                       tj.mintrans.epd.masterdata.service.AuditService audit,
                       @Value("${epd.auth.max-failed-attempts:10}") int maxFailedAttempts,
                       @Value("${epd.auth.lock-minutes:15}") long lockMinutes,
                       @Value("${epd.auth.refresh-ttl-seconds:43200}") long refreshTtlSeconds,
                       @Value("${epd.auth.totp-issuer:e-Rohkhat}") String totpIssuer) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.passwords = passwords;
        this.tokens = tokens;
        this.audit = audit;
        this.maxFailedAttempts = maxFailedAttempts;
        this.lockMinutes = lockMinutes;
        this.refreshTtlSeconds = refreshTtlSeconds;
        this.totpIssuer = totpIssuer;
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
     * Пароль верен, нужен код второго фактора. Выдаётся одноразовый ключ второго шага.
     *
     * <p>{@code setupSecret} не пуст, если второй фактор ещё не подключён: пользователь
     * сканирует QR-код ({@code otpauthUri}) и подтверждает подключение первым кодом.</p>
     */
    public static class SecondFactorRequired extends RuntimeException {
        private final String challengeToken;
        private final String setupSecret;
        private final String otpauthUri;

        SecondFactorRequired(String challengeToken, String setupSecret, String otpauthUri) {
            super("Требуется код второго фактора");
            this.challengeToken = challengeToken;
            this.setupSecret = setupSecret;
            this.otpauthUri = otpauthUri;
        }

        public String challengeToken() {
            return challengeToken;
        }

        public String setupSecret() {
            return setupSecret;
        }

        public String otpauthUri() {
            return otpauthUri;
        }

        public boolean setup() {
            return setupSecret != null;
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
    @Transactional(noRollbackFor = {ResponseStatusException.class, PasswordChangeRequired.class,
            SecondFactorRequired.class})
    public Tokens login(String username, String password) {
        var user = users.findByUsername(username == null ? "" : username.trim()).orElse(null);
        if (user == null) {
            // Пароль всё равно проверяем настоящим BCrypt-хешем, чтобы время ответа не выдавало,
            // существует ли логин (заглушка неверного формата отвечала мгновенно).
            passwords.matches(password == null ? "" : password, dummyHash);
            // Введённый логин в журнал не пишем: в поле логина нередко по ошибке вводят пароль.
            audit.recordAuth("anonymous", null, "LOGIN_ERROR", null, "user_not_found");
            throw invalidCredentials();
        }
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(OffsetDateTime.now())) {
            audit.recordAuth(user.getUsername(), user.getOrganizationRma(), "LOGIN_ERROR", user.getUsername(), "account_locked");
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Учётная запись временно заблокирована из-за неудачных попыток входа. Повторите позже.");
        }
        if (!passwords.matches(password == null ? "" : password, user.getPasswordHash())) {
            registerFailure(user);
            audit.recordAuth(user.getUsername(), user.getOrganizationRma(), "LOGIN_ERROR", user.getUsername(), "invalid_password");
            throw invalidCredentials();
        }
        if (!user.isEnabled()) {
            audit.recordAuth(user.getUsername(), user.getOrganizationRma(), "LOGIN_ERROR", user.getUsername(), "account_disabled");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Учётная запись отключена");
        }

        // Счётчик неудач обнуляет верный пароль — но не у учёток со вторым фактором: там его
        // обнулит только верный код. Иначе, зная пароль, код можно подбирать бесконечно,
        // заново входя паролем после каждых девяти неверных кодов.
        if (!user.secondFactorApplies()) {
            user.setFailedAttempts(0);
            user.setLockedUntil(null);
        }

        if (user.isMustChangePassword()) {
            // Пароль верен, но временный: в систему не пускаем, выдаём ключ для страницы смены.
            users.save(user);
            throw new PasswordChangeRequired(issueKey(user, AuthRefreshToken.PASSWORD_CHANGE, SHORT_KEY_SECONDS));
        }

        if (user.secondFactorApplies()) {
            // Пароль верен, но нужен код из приложения-аутентификатора. Сессию не выдаём —
            // только ключ второго шага. Второй фактор ещё не подключён — выдаём секрет для
            // QR-кода; повторная попытка входа отдаёт тот же секрет, чтобы уже отсканированный
            // код не перестал подходить.
            String setupSecret = null;
            if (user.getTotpSecret() == null) {
                if (user.getTotpPendingSecret() == null) {
                    user.setTotpPendingSecret(Totp.newSecret());
                }
                setupSecret = user.getTotpPendingSecret();
            }
            users.save(user);
            String challenge = issueKey(user, AuthRefreshToken.SECOND_FACTOR, SHORT_KEY_SECONDS);
            throw new SecondFactorRequired(challenge, setupSecret,
                    setupSecret == null ? null : Totp.otpauthUri(totpIssuer, user.getUsername(), setupSecret));
        }

        return completeLogin(user);
    }

    /**
     * Второй шаг входа: код из приложения-аутентификатора по ключу, выданному после верного
     * пароля. При первой настройке верный код подтверждает подключение второго фактора.
     *
     * <p>Неверный код считается неудачной попыткой входа (общий счётчик с паролем): десять
     * неудач подряд блокируют учётную запись, как и при подборе пароля.</p>
     */
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public Tokens verifySecondFactor(String challengeToken, String code) {
        var stored = refreshTokens.findByTokenHash(hash(challengeToken))
                .filter(t -> t.isUsableFor(AuthRefreshToken.SECOND_FACTOR))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Время на ввод кода истекло, войдите заново"));
        var user = users.findById(stored.getUserId()).orElse(null);
        if (user == null || !user.isEnabled() || user.isMustChangePassword()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Войдите заново");
        }
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(OffsetDateTime.now())) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Учётная запись временно заблокирована из-за неудачных попыток входа. Повторите позже.");
        }
        boolean enrolling = user.getTotpSecret() == null;
        String secret = enrolling ? user.getTotpPendingSecret() : user.getTotpSecret();
        if (secret == null) {
            // Второй фактор сбросили, пока пользователь вводил код, — начать вход сначала.
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Войдите заново");
        }
        long step = Totp.verify(secret, code, java.time.Instant.now(), user.getTotpLastStep());
        if (step < 0) {
            registerFailure(user);
            audit.recordAuth(user.getUsername(), user.getOrganizationRma(), "LOGIN_ERROR", user.getUsername(),
                    "invalid_second_factor");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Неверный код. Введите 6 цифр, которые сейчас показывает приложение.");
        }
        user.setTotpLastStep(step);
        if (enrolling) {
            user.setTotpSecret(secret);
            user.setTotpPendingSecret(null);
            user.setTotpEnrolledAt(OffsetDateTime.now());
            audit.recordAuth(user.getUsername(), user.getOrganizationRma(), "TOTP_ENROLL", user.getUsername(), null);
            log.info("Второй фактор подключён: {}", user.getUsername());
        }
        // Ключ второго шага одноразовый.
        stored.setRevoked(true);
        refreshTokens.save(stored);
        return completeLogin(user);
    }

    /** Все проверки пройдены: сбросить счётчик неудач, записать вход, выдать сессию. */
    private Tokens completeLogin(AppUser user) {
        user.setFailedAttempts(0);
        user.setLockedUntil(null);
        user.setLastLoginAt(OffsetDateTime.now());
        users.save(user);
        // Вход в журнал аудита: раньше эти записи приходили из событий Keycloak отдельной
        // выгрузкой, теперь их пишет сама платформа в момент входа (с IP и браузером).
        audit.recordAuth(user.getUsername(), user.getOrganizationRma(), "LOGIN", user.getUsername(), null);
        return issueTokens(user);
    }

    @Transactional
    public Tokens refresh(String refreshToken) {
        var stored = refreshTokens.findByTokenHash(hash(refreshToken)).orElse(null);
        // Только токен обновления: ключ смены пароля и ключ второго шага входа лежат в той же
        // таблице, но сессию по ним получить нельзя (иначе вход без кода второго фактора).
        if (stored == null || !stored.isUsableFor(AuthRefreshToken.REFRESH)) {
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
            boolean wasActive = !t.isRevoked();
            t.setRevoked(true);
            refreshTokens.save(t);
            if (wasActive) {
                users.findById(t.getUserId()).ifPresent(u ->
                        audit.recordAuth(u.getUsername(), u.getOrganizationRma(), "LOGOUT", u.getUsername(), null));
            }
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
                    .filter(t -> t.isUsableFor(AuthRefreshToken.PASSWORD_CHANGE))
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
        String refresh = issueKey(user, AuthRefreshToken.REFRESH, refreshTtlSeconds);
        return new Tokens(access, tokens.accessTtlSeconds(), refresh, refreshTtlSeconds, "Bearer");
    }

    /** Одноразовый ключ заданного назначения; в базу пишется только его отпечаток. */
    private String issueKey(AppUser user, String purpose, long ttlSeconds) {
        byte[] raw = new byte[48];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        var entity = new AuthRefreshToken();
        entity.setUserId(user.getId());
        entity.setTokenHash(hash(token));
        entity.setPurpose(purpose);
        entity.setExpiresAt(OffsetDateTime.now().plusSeconds(ttlSeconds));
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
