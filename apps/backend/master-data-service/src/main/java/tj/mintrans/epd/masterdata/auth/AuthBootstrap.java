package tj.mintrans.epd.masterdata.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tj.mintrans.epd.masterdata.domain.AppUser;
import tj.mintrans.epd.masterdata.repository.AppUserRepository;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Разовый перенос учётных записей при отказе от Keycloak (решение владельца 23.09.2026).
 *
 * <p>Источник — СУЩЕСТВУЮЩИЙ файл экспорта realm, который и так лежит в репозитории и
 * монтируется в Keycloak. Копию с паролями рядом не создаём: второй файл с теми же секретами
 * — лишняя поверхность утечки, а после перехода источник удаляется вместе с Keycloak.</p>
 *
 * <p>Перенос работает ТОЛЬКО когда таблица учётных записей пуста: это миграция, а не
 * синхронизация. Повторный запуск службы ничего не меняет.</p>
 *
 * <p>Если файла нет (боевая установка с нуля), создаётся один администратор платформы из
 * переменных окружения — его пароль в репозитории не хранится и меняется при первом входе.</p>
 */
@Component
public class AuthBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AuthBootstrap.class);

    private final AppUserRepository users;
    private final PasswordEncoder passwords;
    private final boolean enabled;
    private final String realmFile;
    private final String adminUsername;
    private final String adminPassword;
    private final String serviceUsername;
    private final String servicePassword;

    public AuthBootstrap(AppUserRepository users, PasswordEncoder passwords,
                         @Value("${epd.auth.bootstrap.enabled:true}") boolean enabled,
                         @Value("${epd.auth.bootstrap.realm-file:}") String realmFile,
                         @Value("${epd.auth.bootstrap.admin-username:}") String adminUsername,
                         @Value("${epd.auth.bootstrap.admin-password:}") String adminPassword,
                         @Value("${epd.auth.bootstrap.service-username:}") String serviceUsername,
                         @Value("${epd.auth.bootstrap.service-password:}") String servicePassword) {
        this.users = users;
        this.passwords = passwords;
        this.enabled = enabled;
        this.realmFile = realmFile;
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
        this.serviceUsername = serviceUsername;
        this.servicePassword = servicePassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("Перенос учётных записей выключен (epd.auth.bootstrap.enabled=false)");
            return;
        }
        // Служебная учётная запись сверяется с окружением при КАЖДОМ запуске, а не только на
        // пустой таблице: иначе пароль, добавленный в infra/.env после первого старта (или
        // сменённый), никогда не доходил до базы, служба путевых листов получала 401 при входе,
        // и закрытие любого листа падало с 500 на переносе одометра (находка 23.09.2026).
        boolean fresh = users.count() == 0;
        ensureServiceAccountFromEnv();
        if (!fresh) {
            return; // уже перенесено — повторно не трогаем
        }
        int created = importFromRealmExport();
        created += createAdminFromEnv();
        if (created == 0) {
            log.warn("Учётных записей нет и перенести неоткуда. Задайте EPD_AUTH_ADMIN_USERNAME "
                    + "и EPD_AUTH_ADMIN_PASSWORD, иначе войти в платформу будет некому.");
        } else {
            log.info("Перенос учётных записей завершён: создано {}", created);
        }
    }

    private int importFromRealmExport() {
        if (realmFile == null || realmFile.isBlank()) {
            return 0;
        }
        File file = new File(realmFile);
        if (!file.isFile()) {
            log.info("Файл экспорта {} недоступен — перенос учётных записей пропущен", realmFile);
            return 0;
        }
        try {
            JsonNode root = new ObjectMapper().readTree(file);
            int created = 0;
            for (JsonNode n : root.path("users")) {
                String username = text(n, "username");
                if (username == null || users.existsByUsername(username)) {
                    continue;
                }
                String password = firstPassword(n);
                if (password == null) {
                    // Служебные учётки без пароля (сервисные аккаунты) не переносим.
                    continue;
                }
                var user = new AppUser();
                user.setUsername(username);
                user.setPasswordHash(passwords.encode(password));
                user.setFirstName(text(n, "firstName"));
                user.setLastName(text(n, "lastName"));
                user.setEmail(text(n, "email"));
                user.setRma(attribute(n, "rma"));
                user.setOrganizationRma(attribute(n, "organizationRma"));
                user.setRoleList(textList(n.path("realmRoles")));
                user.setClientIdList(splitAttribute(attribute(n, "clientIds")));
                user.setEnabled(!n.path("enabled").isBoolean() || n.path("enabled").asBoolean());
                // Переносим «как есть»: это те же логины и пароли, которыми пользовались до
                // перехода. Принудительная смена здесь оборвала бы работу всем разом.
                user.setMustChangePassword(false);
                // Требование второго фактора сохраняем как признак, чтобы не потерять его при
                // переходе; сама проверка появится отдельным шагом (см. spec/PLAN-убрать-keycloak.md).
                user.setTotpRequired(requiresTotp(n));
                users.save(user);
                created++;
            }
            return created;
        } catch (Exception e) {
            log.error("Не удалось перенести учётные записи из {}: {}", realmFile, e.toString());
            return 0;
        }
    }

    /** Начальный администратор из переменных окружения — пароль не хранится в репозитории. */
    private int createAdminFromEnv() {
        if (adminUsername == null || adminUsername.isBlank() || adminPassword == null || adminPassword.isBlank()) {
            return 0;
        }
        if (users.existsByUsername(adminUsername)) {
            return 0;
        }
        var user = new AppUser();
        user.setUsername(adminUsername.trim());
        user.setPasswordHash(passwords.encode(adminPassword));
        user.setLastName("Администратор");
        user.setFirstName("платформы");
        user.setRoleList(List.of("SYSTEM_ADMIN"));
        user.setEnabled(true);
        // Пароль задан в окружении — на первом входе требуем заменить его на личный.
        user.setMustChangePassword(true);
        users.save(user);
        log.info("Создан начальный администратор платформы: {}", adminUsername);
        return 1;
    }

    /**
     * Служебная учётная запись межсервисных вызовов (роль {@code API_INTEGRATOR}). Ею входит
     * служба путевых листов, когда пользовательского токена нет: агрегатор, планировщик,
     * перенос одометра при закрытии листа. Раньше эту роль играл client-credentials клиент
     * Keycloak. Пароль — только из окружения; смена пароля для неё не требуется, иначе
     * межсервисные вызовы встанут.
     *
     * <p>Окружение — единственный источник правды для этой записи: при каждом запуске она
     * создаётся, если её нет, а у существующей пароль, роль и признак «включена» приводятся к
     * заданным. Так смена пароля в infra/.env вступает в силу перезапуском служб, без ручной
     * правки базы. Возвращает 1, если запись создана.</p>
     */
    int ensureServiceAccountFromEnv() {
        if (serviceUsername == null || serviceUsername.isBlank()
                || servicePassword == null || servicePassword.isBlank()) {
            log.warn("Служебная учётная запись не задана (SERVICE_ACCOUNT_USERNAME / SERVICE_ACCOUNT_PASSWORD "
                    + "в infra/.env): служба путевых листов не сможет перенести одометр при закрытии листа "
                    + "и обслужить вызовы без пользователя (агрегатор, планировщик)");
            return 0;
        }
        String username = serviceUsername.trim();
        var existing = users.findByUsername(username);
        if (existing.isEmpty()) {
            var user = new AppUser();
            user.setUsername(username);
            user.setPasswordHash(passwords.encode(servicePassword));
            user.setLastName("Служебная");
            user.setFirstName("учётная запись");
            user.setRoleList(List.of("API_INTEGRATOR"));
            user.setEnabled(true);
            user.setMustChangePassword(false);
            users.save(user);
            log.info("Создана служебная учётная запись межсервисных вызовов: {}", username);
            return 1;
        }
        var user = existing.get();
        boolean changed = false;
        if (user.getPasswordHash() == null || !passwords.matches(servicePassword, user.getPasswordHash())) {
            user.setPasswordHash(passwords.encode(servicePassword));
            changed = true;
        }
        if (!user.roleList().contains("API_INTEGRATOR")) {
            var roles = new ArrayList<>(user.roleList());
            roles.add("API_INTEGRATOR");
            user.setRoleList(roles);
            changed = true;
        }
        if (!user.isEnabled() || user.isMustChangePassword()) {
            user.setEnabled(true);
            user.setMustChangePassword(false);
            changed = true;
        }
        if (changed) {
            users.save(user);
            log.info("Служебная учётная запись {} приведена к настройкам окружения", username);
        }
        return 0;
    }

    private static boolean requiresTotp(JsonNode user) {
        for (JsonNode a : user.path("requiredActions")) {
            if ("CONFIGURE_TOTP".equals(a.asText())) {
                return true;
            }
        }
        return false;
    }

    private static String firstPassword(JsonNode user) {
        for (JsonNode c : user.path("credentials")) {
            if ("password".equals(c.path("type").asText()) && c.path("value").isTextual()) {
                String v = c.path("value").asText();
                if (!v.isBlank()) {
                    return v;
                }
            }
        }
        return null;
    }

    /** Атрибут пользователя: в экспорте они лежат массивами значений. */
    private static String attribute(JsonNode user, String name) {
        JsonNode arr = user.path("attributes").path(name);
        if (arr.isArray() && !arr.isEmpty() && arr.get(0).isTextual()) {
            String v = arr.get(0).asText();
            return v.isBlank() ? null : v;
        }
        return null;
    }

    private static List<String> splitAttribute(String raw) {
        List<String> out = new ArrayList<>();
        if (raw != null) {
            for (String s : raw.split(",")) {
                String t = s.trim();
                if (!t.isEmpty()) {
                    out.add(t);
                }
            }
        }
        return out;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isTextual() && !v.asText().isBlank() ? v.asText() : null;
    }

    private static List<String> textList(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            arr.forEach(n -> {
                if (n.isTextual() && !n.asText().isBlank()) {
                    out.add(n.asText());
                }
            });
        }
        return out;
    }
}
