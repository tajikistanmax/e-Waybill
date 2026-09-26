package tj.mintrans.epd.masterdata.auth;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tj.mintrans.epd.masterdata.domain.AppUser;
import tj.mintrans.epd.masterdata.repository.AppUserRepository;
import tj.mintrans.epd.masterdata.repository.AuthRefreshTokenRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Справочник учётных записей платформы: то же, что раньше делал административный интерфейс
 * Keycloak, но в нашей базе ({@code app_user}).
 *
 * <p>Набор операций и форма записи повторяют прежний клиент Keycloak, поэтому страница
 * «Доступы» и её API не изменились: перевозчик по-прежнему заводит логины сотрудникам,
 * блокирует, сбрасывает пароль и меняет роль.</p>
 */
@Service
public class UserDirectory {

    /** Учётная запись в том виде, в каком её отдаёт API управления доступом. */
    public record OrgUser(String id, String username, String firstName, String lastName,
                          boolean enabled, String rma, String organizationRma, List<String> roles,
                          boolean secondFactorRequired, boolean secondFactorEnrolled) {
        public OrgUser(String id, String username, String firstName, String lastName,
                       boolean enabled, String rma, String organizationRma, List<String> roles) {
            this(id, username, firstName, lastName, enabled, rma, organizationRma, roles, false, false);
        }
    }

    private final AppUserRepository users;
    private final AuthRefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwords;

    public UserDirectory(AppUserRepository users, AuthRefreshTokenRepository refreshTokens,
                         PasswordEncoder passwords) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.passwords = passwords;
    }

    /**
     * Управление доступом доступно всегда: учётные записи лежат в нашей базе. Раньше здесь
     * проверялась доступность внешней службы, и при её отсутствии страница «Доступы»
     * показывала «провижининг отключён».
     */
    public boolean isAvailable() {
        return true;
    }

    @Transactional(readOnly = true)
    public List<OrgUser> listByOrganizations(Iterable<String> organizationRmas) {
        List<String> rmas = new ArrayList<>();
        organizationRmas.forEach(rmas::add);
        if (rmas.isEmpty()) {
            return List.of();
        }
        return users.findByOrganizationRmaIn(rmas).stream().map(UserDirectory::toOrgUser).toList();
    }

    @Transactional(readOnly = true)
    public OrgUser getUser(String userId) {
        return toOrgUser(entity(userId));
    }

    /**
     * Создание учётной записи с временным паролем: до его смены вход в платформу не даётся
     * (прежнее поведение обязательного действия Keycloak, только страница смены теперь своя).
     */
    @Transactional
    public String createUser(String username, String firstName, String lastName, String email,
                             String rma, String organizationRma, String temporaryPassword,
                             List<String> clientIds) {
        String login = username == null ? "" : username.trim();
        if (login.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Укажите логин");
        }
        if (users.existsByUsername(login)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Логин «%s» уже занят".formatted(login));
        }
        AuthService.assertPasswordPolicy(temporaryPassword, login);
        var user = new AppUser();
        user.setUsername(login);
        user.setPasswordHash(passwords.encode(temporaryPassword));
        user.setFirstName(trimToNull(firstName));
        user.setLastName(trimToNull(lastName));
        user.setEmail(trimToNull(email));
        user.setRma(trimToNull(rma));
        user.setOrganizationRma(trimToNull(organizationRma));
        user.setClientIdList(clientIds);
        user.setEnabled(true);
        user.setMustChangePassword(true);
        return users.save(user).getId().toString();
    }

    /** Назначение единственной роли из белого списка (остальные снимаются). */
    @Transactional
    public void setSingleRealmRole(String userId, String roleName, Iterable<String> revocableRoles) {
        var user = entity(userId);
        List<String> keep = new ArrayList<>();
        List<String> revocable = new ArrayList<>();
        revocableRoles.forEach(revocable::add);
        for (String existing : user.roleList()) {
            // Роли вне белого списка (например, служебные) не трогаем.
            if (!revocable.contains(existing)) {
                keep.add(existing);
            }
        }
        if (!keep.contains(roleName)) {
            keep.add(roleName);
        }
        user.setRoleList(keep);
        users.save(user);
    }

    @Transactional
    public void setEnabled(String userId, boolean enabled) {
        var user = entity(userId);
        user.setEnabled(enabled);
        users.save(user);
        if (!enabled) {
            // Отключённая учётка не должна продолжать работать по уже выданному токену обновления.
            refreshTokens.revokeAllForUser(user.getId());
        }
    }

    @Transactional
    public void resetPassword(String userId, String password) {
        var user = entity(userId);
        AuthService.assertPasswordPolicy(password, user.getUsername());
        user.setPasswordHash(passwords.encode(password));
        // Внешняя система входит программой: страницы смены пароля у неё нет, новый пароль
        // администратор передаёт ей сам (как в «Роҳхат», company_for_api). Людям — временный.
        user.setMustChangePassword(!user.roleList().contains("API_INTEGRATOR"));
        user.setFailedAttempts(0);
        user.setLockedUntil(null);
        users.save(user);
        refreshTokens.revokeAllForUser(user.getId());
    }

    /**
     * Учётная запись внешней системы-интегратора (сверка 25.09, G2): роль {@code API_INTEGRATOR},
     * заданные каналы, без временного пароля и второго фактора — входит программа, а не человек.
     * Выданные токены обновления гасятся: новые каналы действуют со следующего входа.
     */
    @Transactional
    public void configureIntegrator(String userId, List<String> channels) {
        var user = entity(userId);
        user.setRoleList(List.of("API_INTEGRATOR"));
        user.setApiChannelList(channels);
        user.setOrganizationRma(null);
        user.setMustChangePassword(false);
        user.setTotpRequired(false);
        users.save(user);
        refreshTokens.revokeAllForUser(user.getId());
    }

    /**
     * Сброс второго фактора (потерян или заменён телефон): секрет удаляется, при следующем
     * входе пользователь подключит приложение заново. Действующие сессии гасятся — ими мог
     * пользоваться тот, у кого теперь телефон.
     */
    @Transactional
    public void resetSecondFactor(String userId) {
        var user = entity(userId);
        user.setTotpSecret(null);
        user.setTotpPendingSecret(null);
        user.setTotpLastStep(null);
        user.setTotpEnrolledAt(null);
        users.save(user);
        refreshTokens.revokeAllForUser(user.getId());
    }

    /** Перевод учётной записи в другую организацию (или «без организации» — null). */
    @Transactional
    public void setOrganization(String userId, String organizationRma) {
        var user = entity(userId);
        user.setOrganizationRma(trimToNull(organizationRma));
        users.save(user);
    }

    /** Обязателен ли второй фактор для учётной записи. */
    @Transactional
    public void setSecondFactorRequired(String userId, boolean required) {
        var user = entity(userId);
        user.setTotpRequired(required);
        users.save(user);
    }

    @Transactional
    public void deleteUser(String userId) {
        var user = entity(userId);
        refreshTokens.revokeAllForUser(user.getId());
        users.delete(user);
    }

    /** Список контрагентов кабинета накладных (claim client_ids). */
    @Transactional
    public void setClientIds(String userId, List<String> clientIds) {
        var user = entity(userId);
        user.setClientIdList(clientIds);
        users.save(user);
    }

    private AppUser entity(String userId) {
        UUID id;
        try {
            id = UUID.fromString(userId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Пользователь не найден");
        }
        return users.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Пользователь не найден"));
    }

    private static OrgUser toOrgUser(AppUser u) {
        return new OrgUser(u.getId().toString(), u.getUsername(), u.getFirstName(), u.getLastName(),
                u.isEnabled(), u.getRma(), u.getOrganizationRma(), u.roleList(),
                u.isTotpRequired(), u.getTotpSecret() != null);
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
