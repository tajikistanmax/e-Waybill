package tj.mintrans.epd.masterdata.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tj.mintrans.epd.masterdata.domain.AppUser;
import tj.mintrans.epd.masterdata.domain.AuthRefreshToken;
import tj.mintrans.epd.masterdata.repository.AppUserRepository;
import tj.mintrans.epd.masterdata.repository.AuthRefreshTokenRepository;
import tj.mintrans.epd.masterdata.service.AuditService;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Вход в платформу: ветки, найденные живой проверкой 23.09.2026.
 *
 * <p>Живьём сломано было так: первый вход с временным паролем выдавал ключ смены, но страница
 * смены отвечала «ключ недействителен» (запись ключа откатывалась вместе с исключением), а
 * счётчик неудачных попыток не рос — блокировка после 10 неудач не срабатывала никогда.</p>
 */
class AuthServiceTest {

    private static final String PASSWORD = "Correct-Horse-2026";

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private AppUserRepository users;
    private AuthRefreshTokenRepository tokens;
    private TokenIssuer issuer;
    private AuthService auth;
    private AppUser user;

    @BeforeEach
    void setUp() {
        users = mock(AppUserRepository.class);
        tokens = mock(AuthRefreshTokenRepository.class);
        issuer = mock(TokenIssuer.class);
        when(issuer.accessToken(any())).thenReturn("access");
        when(issuer.accessTtlSeconds()).thenReturn(1800L);
        auth = new AuthService(users, tokens, encoder, issuer, mock(AuditService.class), 10, 15, 43200, "e-Rohkhat");

        user = new AppUser();
        user.setId(UUID.randomUUID());
        user.setUsername("992900000001");
        user.setPasswordHash(encoder.encode(PASSWORD));
        user.setEnabled(true);
        when(users.findByUsername("992900000001")).thenReturn(Optional.of(user));
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
        when(users.save(any())).thenAnswer(i -> i.getArgument(0));
        when(tokens.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    /**
     * Контракт транзакции: исключения, которыми сообщается отказ во входе, НЕ должны откатывать
     * записи (счётчик неудач, ключ смены пароля). Без noRollbackFor Spring откатывает их молча —
     * модульный тест на моках этого не видит, поэтому проверяем саму аннотацию.
     */
    @Test
    void loginDoesNotRollBackOnRefusal() throws Exception {
        Transactional tx = AuthService.class.getMethod("login", String.class, String.class)
                .getAnnotation(Transactional.class);
        assertThat(tx).isNotNull();
        assertThat(Arrays.asList(tx.noRollbackFor()))
                .contains(ResponseStatusException.class, AuthService.PasswordChangeRequired.class);
    }

    @Test
    void tenFailuresLockTheAccountEvenForCorrectPassword() {
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> auth.login("992900000001", "wrong"))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
        }
        assertThat(user.getLockedUntil()).isAfter(OffsetDateTime.now());
        assertThatThrownBy(() -> auth.login("992900000001", PASSWORD))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
    }

    @Test
    void temporaryPasswordGivesStoredChangeTokenNotSession() {
        user.setMustChangePassword(true);
        assertThatThrownBy(() -> auth.login("992900000001", PASSWORD))
                .isInstanceOf(AuthService.PasswordChangeRequired.class);
        // Ключ смены записан (живой баг: запись откатывалась вместе с исключением).
        verify(tokens, atLeastOnce()).save(any(AuthRefreshToken.class));
    }

    @Test
    void changeTokenCannotBeUsedAsRefreshToken() {
        user.setMustChangePassword(true);
        stubToken("change-key", AuthRefreshToken.PASSWORD_CHANGE);
        assertThatThrownBy(() -> auth.refresh("change-key"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void firstLoginChangeWorksWithChangeToken() {
        user.setMustChangePassword(true);
        stubToken("change-key", AuthRefreshToken.PASSWORD_CHANGE);
        auth.changePassword(null, "change-key", null, "Brand-New-Pass-2026");
        assertThat(user.isMustChangePassword()).isFalse();
        assertThat(encoder.matches("Brand-New-Pass-2026", user.getPasswordHash())).isTrue();
        verify(tokens).revokeAllForUser(user.getId());
    }

    @Test
    void refreshTokenCannotChangePasswordWithoutCurrentOne() {
        user.setMustChangePassword(false);
        stubToken("session-refresh", AuthRefreshToken.REFRESH);
        assertThatThrownBy(() -> auth.changePassword(null, "session-refresh", null, "Brand-New-Pass-2026"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
        assertThat(encoder.matches(PASSWORD, user.getPasswordHash())).isTrue();
    }

    @Test
    void anonymousChangeWithoutKeyIs401NotServerError() {
        assertThatThrownBy(() -> auth.changePassword(null, null, null, "Brand-New-Pass-2026"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void unknownLoginIsSame401() {
        when(users.findByUsername(anyString())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> auth.login("nobody", "whatever"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    // ---------------------------------------------------------------- второй фактор (находка 27)

    @Test
    void requiredSecondFactorWithoutEnrollmentGivesSetupNotSession() {
        user.setTotpRequired(true);
        var e = catchSecondFactor();
        assertThat(e.setup()).isTrue();
        assertThat(e.setupSecret()).hasSize(32);
        assertThat(e.otpauthUri()).contains("secret=" + e.setupSecret());
        assertThat(user.getTotpSecret()).isNull();
        assertThat(user.getTotpPendingSecret()).isEqualTo(e.setupSecret());
        verify(issuer, never()).accessToken(any());
        // Повторная попытка — тот же секрет: уже отсканированный QR-код не должен «протухнуть».
        assertThat(catchSecondFactor().setupSecret()).isEqualTo(e.setupSecret());
    }

    @Test
    void enrollmentCompletesWithFirstValidCode() {
        user.setTotpRequired(true);
        var e = catchSecondFactor();
        stubToken(e.challengeToken(), AuthRefreshToken.SECOND_FACTOR);
        var result = auth.verifySecondFactor(e.challengeToken(), currentCode(e.setupSecret()));
        assertThat(result.accessToken()).isEqualTo("access");
        assertThat(user.getTotpSecret()).isEqualTo(e.setupSecret());
        assertThat(user.getTotpPendingSecret()).isNull();
        assertThat(user.getTotpEnrolledAt()).isNotNull();
    }

    @Test
    void enrolledUserNeedsCodeAndGetsSessionOnlyWithIt() {
        user.setTotpSecret(Totp.newSecret());
        var e = catchSecondFactor();
        assertThat(e.setup()).isFalse();
        stubToken(e.challengeToken(), AuthRefreshToken.SECOND_FACTOR);
        assertThatThrownBy(() -> auth.verifySecondFactor(e.challengeToken(), "000000"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
        assertThat(user.getFailedAttempts()).isEqualTo(1);
        assertThat(auth.verifySecondFactor(e.challengeToken(), currentCode(user.getTotpSecret())).accessToken())
                .isEqualTo("access");
        assertThat(user.getFailedAttempts()).isZero();
    }

    /** Главная дыра, которую закрывает V81: ключ второго шага не годится как токен обновления. */
    @Test
    void secondFactorChallengeCannotBeUsedAsRefreshToken() {
        user.setTotpSecret(Totp.newSecret());
        var e = catchSecondFactor();
        stubToken(e.challengeToken(), AuthRefreshToken.SECOND_FACTOR);
        assertThatThrownBy(() -> auth.refresh(e.challengeToken()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
        verify(issuer, never()).accessToken(any());
    }

    @Test
    void refreshTokenCannotReplaceSecondFactorCode() {
        user.setTotpSecret(Totp.newSecret());
        stubToken("session-refresh", AuthRefreshToken.REFRESH);
        assertThatThrownBy(() -> auth.verifySecondFactor("session-refresh", currentCode(user.getTotpSecret())))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    /**
     * Верный пароль не обнуляет счётчик неудач у учётки со вторым фактором: иначе код можно
     * подбирать бесконечно, заново входя паролем после каждых девяти неверных кодов.
     */
    @Test
    void correctPasswordDoesNotResetFailedCodeCounter() {
        user.setTotpSecret(Totp.newSecret());
        for (int round = 0; round < 2; round++) {
            var e = catchSecondFactor();
            stubToken(e.challengeToken(), AuthRefreshToken.SECOND_FACTOR);
            for (int i = 0; i < 5; i++) {
                assertThatThrownBy(() -> auth.verifySecondFactor(e.challengeToken(), "000000"))
                        .isInstanceOf(ResponseStatusException.class);
            }
        }
        assertThat(user.getLockedUntil()).isAfter(OffsetDateTime.now());
        assertThatThrownBy(() -> auth.login("992900000001", PASSWORD))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
    }

    @Test
    void temporaryPasswordComesBeforeSecondFactor() {
        user.setTotpRequired(true);
        user.setMustChangePassword(true);
        assertThatThrownBy(() -> auth.login("992900000001", PASSWORD))
                .isInstanceOf(AuthService.PasswordChangeRequired.class);
    }

    @Test
    void loginDoesNotRollBackOnSecondFactorRefusal() throws Exception {
        Transactional tx = AuthService.class.getMethod("login", String.class, String.class)
                .getAnnotation(Transactional.class);
        assertThat(Arrays.asList(tx.noRollbackFor())).contains(AuthService.SecondFactorRequired.class);
        Transactional verify = AuthService.class.getMethod("verifySecondFactor", String.class, String.class)
                .getAnnotation(Transactional.class);
        assertThat(Arrays.asList(verify.noRollbackFor())).contains(ResponseStatusException.class);
    }

    private AuthService.SecondFactorRequired catchSecondFactor() {
        try {
            auth.login("992900000001", PASSWORD);
        } catch (AuthService.SecondFactorRequired e) {
            return e;
        }
        throw new AssertionError("ожидался запрос второго фактора");
    }

    private static String currentCode(String secret) {
        return Totp.code(Totp.base32Decode(secret), Totp.step(java.time.Instant.now()));
    }

    private void stubToken(String raw, String purpose) {
        var t = new AuthRefreshToken();
        t.setUserId(user.getId());
        t.setTokenHash(AuthService.hash(raw));
        t.setPurpose(purpose);
        t.setExpiresAt(OffsetDateTime.now().plusMinutes(10));
        when(tokens.findByTokenHash(AuthService.hash(raw))).thenReturn(Optional.of(t));
    }
}
