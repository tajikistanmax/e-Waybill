package tj.mintrans.epd.masterdata.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tj.mintrans.epd.masterdata.domain.AppUser;
import tj.mintrans.epd.masterdata.repository.AppUserRepository;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Какие учётки интеграции (роль {@code API_INTEGRATOR}) можно вести из интерфейса
 * (сверка 25.09, G2).
 *
 * <p>Учётки внешних систем (КВД, Smart City…) заводит администратор платформы на странице
 * «Пользователи» с набором каналов — их он же блокирует, сбрасывает им пароль, меняет каналы и
 * удаляет. Учётки из настроек стенда — служебная межсервисная ({@code SERVICE_ACCOUNT_USERNAME})
 * и агрегатор ({@code AGGREGATOR_USERNAME}) — остаются только за окружением: их пароль и каналы
 * при каждом запуске приводятся к {@code infra/.env}, и правка из интерфейса либо откатилась
 * бы, либо остановила бы межсервисные вызовы.</p>
 */
@Component
public class IntegratorAccounts {

    private final AppUserRepository users;
    private final Set<String> environmentUsernames = new HashSet<>();

    public IntegratorAccounts(AppUserRepository users,
                              @Value("${epd.auth.bootstrap.service-username:}") String serviceUsername,
                              @Value("${epd.auth.bootstrap.aggregator-username:}") String aggregatorUsername) {
        this.users = users;
        for (String u : new String[] {serviceUsername, aggregatorUsername}) {
            if (u != null && !u.isBlank()) {
                environmentUsernames.add(u.trim());
            }
        }
    }

    public static boolean isIntegrator(AppUser u) {
        return u.roleList().contains("API_INTEGRATOR");
    }

    /** Учётка интеграции из настроек стенда или без каналов (служебная) — не для интерфейса. */
    public boolean environmentManaged(AppUser u) {
        return isIntegrator(u) && (u.apiChannelList() == null || environmentUsernames.contains(u.getUsername()));
    }

    /** Внешняя система, заведённая в интерфейсе: её ведёт администратор платформы. */
    public boolean manageable(String userId) {
        try {
            return users.findById(UUID.fromString(userId))
                    .map(u -> isIntegrator(u) && !environmentManaged(u))
                    .orElse(false);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
