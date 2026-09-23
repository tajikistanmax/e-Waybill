package tj.mintrans.epd.masterdata.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import tj.mintrans.epd.masterdata.domain.AppUser;
import tj.mintrans.epd.masterdata.repository.AppUserRepository;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Служебная учётная запись межсервисных вызовов (находка 23.09.2026): до правки она создавалась
 * только на пустой таблице пользователей, поэтому пароль, добавленный в infra/.env позже, до
 * базы не доходил — служба путевых листов не могла войти, и закрытие любого листа падало с 500
 * на переносе одометра в справочник ТС.
 */
class AuthBootstrapServiceAccountTest {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private AppUserRepository users;

    @BeforeEach
    void setUp() {
        users = mock(AppUserRepository.class);
        when(users.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private AuthBootstrap bootstrap(String username, String password) {
        return new AuthBootstrap(users, encoder, true, "", "", "", username, password);
    }

    @Test
    void createsServiceAccountEvenWhenOtherUsersAlreadyExist() {
        when(users.count()).thenReturn(17L);
        when(users.findByUsername("epd-service")).thenReturn(Optional.empty());

        bootstrap("epd-service", "Svc-Secret-2026").run(null);

        var saved = org.mockito.ArgumentCaptor.forClass(AppUser.class);
        verify(users).save(saved.capture());
        assertThat(saved.getValue().getUsername()).isEqualTo("epd-service");
        assertThat(saved.getValue().roleList()).containsExactly("API_INTEGRATOR");
        assertThat(saved.getValue().isEnabled()).isTrue();
        assertThat(saved.getValue().isMustChangePassword()).isFalse();
        assertThat(encoder.matches("Svc-Secret-2026", saved.getValue().getPasswordHash())).isTrue();
    }

    @Test
    void realignsPasswordRoleAndStateOfExistingAccountWithEnvironment() {
        var existing = new AppUser();
        existing.setUsername("epd-service");
        existing.setPasswordHash(encoder.encode("old-password"));
        existing.setRoleList(List.of("DISPATCHER"));
        existing.setEnabled(false);
        existing.setMustChangePassword(true);
        when(users.count()).thenReturn(18L);
        when(users.findByUsername("epd-service")).thenReturn(Optional.of(existing));

        bootstrap("epd-service", "New-Secret-2026").run(null);

        verify(users).save(existing);
        assertThat(encoder.matches("New-Secret-2026", existing.getPasswordHash())).isTrue();
        assertThat(existing.roleList()).contains("API_INTEGRATOR");
        assertThat(existing.isEnabled()).isTrue();
        assertThat(existing.isMustChangePassword()).isFalse();
    }

    @Test
    void leavesAlignedAccountUntouched() {
        var existing = new AppUser();
        existing.setUsername("epd-service");
        existing.setPasswordHash(encoder.encode("Same-Secret-2026"));
        existing.setRoleList(List.of("API_INTEGRATOR"));
        existing.setEnabled(true);
        existing.setMustChangePassword(false);
        when(users.count()).thenReturn(18L);
        when(users.findByUsername("epd-service")).thenReturn(Optional.of(existing));

        bootstrap("epd-service", "Same-Secret-2026").run(null);

        verify(users, never()).save(any());
    }

    @Test
    void withoutPasswordInEnvironmentNothingIsCreated() {
        when(users.count()).thenReturn(17L);

        bootstrap("epd-service", "").run(null);

        verify(users, never()).findByUsername(anyString());
        verify(users, never()).save(any());
    }
}
