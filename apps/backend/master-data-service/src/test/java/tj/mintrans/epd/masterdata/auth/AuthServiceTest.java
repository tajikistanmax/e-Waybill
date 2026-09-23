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
        auth = new AuthService(users, tokens, encoder, issuer, mock(AuditService.class), 10, 15, 43200);

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
        stubToken("change-key");
        assertThatThrownBy(() -> auth.refresh("change-key"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void firstLoginChangeWorksWithChangeToken() {
        user.setMustChangePassword(true);
        stubToken("change-key");
        auth.changePassword(null, "change-key", null, "Brand-New-Pass-2026");
        assertThat(user.isMustChangePassword()).isFalse();
        assertThat(encoder.matches("Brand-New-Pass-2026", user.getPasswordHash())).isTrue();
        verify(tokens).revokeAllForUser(user.getId());
    }

    @Test
    void refreshTokenCannotChangePasswordWithoutCurrentOne() {
        user.setMustChangePassword(false);
        stubToken("session-refresh");
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

    private void stubToken(String raw) {
        var t = new AuthRefreshToken();
        t.setUserId(user.getId());
        t.setTokenHash(AuthService.hash(raw));
        t.setExpiresAt(OffsetDateTime.now().plusMinutes(10));
        when(tokens.findByTokenHash(AuthService.hash(raw))).thenReturn(Optional.of(t));
    }
}
