package tj.mintrans.epd.masterdata.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tj.mintrans.epd.masterdata.auth.UserDirectory;
import tj.mintrans.epd.masterdata.config.CurrentUser;
import tj.mintrans.epd.masterdata.config.TenantScope;
import tj.mintrans.epd.masterdata.service.AuditService;

import java.security.SecureRandom;
import java.util.List;
import java.util.Set;

/**
 * Логины и роли сотрудников перевозчика в модуле путевого листа.
 *
 * <p>Администратор компании / филиала выдаёт сотруднику (из числа заведённых
 * синхронизацией) вход в систему и <b>одну</b> роль модуля из белого списка.
 * {@code organization_rma} логина принудительно = организация из области
 * вызывающего — выдать доступ в чужую организацию нельзя.</p>
 *
 * <p>Роли выше операционных (COMPANY_ADMIN и платформенные) через этот эндпоинт
 * не выдаются. BRANCH_ADMIN может выдать только администратор компании / системы.</p>
 */
@RestController
@RequestMapping("/api/v1/org-users")
public class OrgUserController {

    /** Операционные роли, которые перевозчик раздаёт своим сотрудникам. */
    private static final Set<String> GRANTABLE = Set.of(
            "DISPATCHER", "DOCTOR", "MECHANIC", "DRIVER", "ACCOUNTANT", "FUEL_STATION");
    /** BRANCH_ADMIN — тоже раздаётся перевозчиком, но только администратором компании. */
    private static final String BRANCH_ADMIN = "BRANCH_ADMIN";
    /** COMPANY_ADMIN — только системным администратором (см. normalizeRole). */
    private static final String COMPANY_ADMIN = "COMPANY_ADMIN";
    /**
     * Внешние кабинеты накладных (MIGRATION.md 1.1/3.11): логины грузоотправителя и экспедитора
     * выдаёт перевозчик своим контрагентам (нужен атрибут clientIds), таможенника — только
     * системный администратор (государственная роль, не относится к конкретному перевозчику).
     */
    private static final Set<String> CLIENT_ROLES = Set.of("CLIENT_SENDER", "CLIENT_FORWARDER");
    private static final String CUSTOMS_OFFICER = "CUSTOMS_OFFICER";
    private static final Set<String> ALL_REVOCABLE;

    static {
        var s = new java.util.HashSet<>(GRANTABLE);
        s.add(BRANCH_ADMIN);
        s.add(COMPANY_ADMIN);
        s.addAll(CLIENT_ROLES);
        s.add(CUSTOMS_OFFICER);
        ALL_REVOCABLE = Set.copyOf(s);
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] PWD_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789".toCharArray();

    /** Справочник учётных записей платформы (таблица app_user). До 23.09.2026 — Keycloak. */
    private final tj.mintrans.epd.masterdata.auth.UserDirectory keycloak;
    private final CurrentUser currentUser;
    private final TenantScope tenantScope;
    private final AuditService audit;

    public OrgUserController(tj.mintrans.epd.masterdata.auth.UserDirectory keycloak, CurrentUser currentUser,
                             TenantScope tenantScope, AuditService audit) {
        this.keycloak = keycloak;
        this.currentUser = currentUser;
        this.tenantScope = tenantScope;
        this.audit = audit;
    }

    public record CreateRequest(
            @NotBlank String username,
            String firstName,
            String lastName,
            String email,
            @Pattern(regexp = "\\d{9,10}", message = "РМА сотрудника: 9–10 цифр") String personRma,
            @NotBlank @Pattern(regexp = "\\d{9,10}", message = "РМА организации: 9–10 цифр") String organizationRma,
            @NotBlank String role,
            String password,
            // Контрагенты внешнего пользователя кабинета накладных (идентификаторы Client):
            // обязателен для CLIENT_SENDER / CLIENT_FORWARDER, игнорируется для остальных ролей.
            List<String> clientIds) {
    }

    public record EnabledRequest(boolean enabled) {
    }

    /**
     * Учётка + (только при создании/сбросе) временный пароль.
     *
     * <p>{@code manageable} — вправе ли вызывающий блокировать, сбрасывать пароль, менять роль и
     * удалять эту учётную запись (см. {@link #canManage}); интерфейс по нему прячет кнопки.</p>
     */
    public record OrgUserView(String id, String username, String firstName, String lastName,
                              boolean enabled, String rma, String organizationRma,
                              List<String> roles, String temporaryPassword, boolean manageable) {
        static OrgUserView of(UserDirectory.OrgUser u, String tempPassword, boolean manageable) {
            return new OrgUserView(u.id(), u.username(), u.firstName(), u.lastName(), u.enabled(),
                    u.rma(), u.organizationRma(), u.roles(), tempPassword, manageable);
        }
    }

    @GetMapping("/enabled")
    public boolean provisioningEnabled() {
        return keycloak.isAvailable();
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','COMPANY_ADMIN','BRANCH_ADMIN')")
    public List<OrgUserView> list(@RequestParam(required = false) String organizationRma) {
        Iterable<String> scope = scopeFor(organizationRma);
        return keycloak.listByOrganizations(scope).stream()
                .map(u -> OrgUserView.of(u, null, canManage(u)))
                .toList();
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','COMPANY_ADMIN','BRANCH_ADMIN')")
    public ResponseEntity<OrgUserView> create(@Valid @RequestBody CreateRequest req) {
        requireWritable(req.organizationRma());
        String role = normalizeRole(req.role());
        String password = (req.password() == null || req.password().isBlank())
                ? randomPassword() : req.password().trim();
        List<String> clientIds = normalizeClientIds(role, req.clientIds());
        // Занятый логин (409) и нарушение парольной политики (422) справочник отдаёт сам,
        // с понятным текстом — отдельные перехваты ошибок внешней службы больше не нужны.
        String id = keycloak.createUser(req.username().trim(), req.firstName(), req.lastName(),
                req.email(), blankToNull(req.personRma()), req.organizationRma(), password, clientIds);
        keycloak.setSingleRealmRole(id, role, ALL_REVOCABLE);
        audit.record(AuditService.CREATE, "ORG_USER", req.username().trim(), null,
                role + " @ " + req.organizationRma());
        UserDirectory.OrgUser created = keycloak.getUser(id);
        return ResponseEntity.status(HttpStatus.CREATED).body(OrgUserView.of(created, password, true));
    }

    @PatchMapping("/{id}/enabled")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','COMPANY_ADMIN','BRANCH_ADMIN')")
    public OrgUserView setEnabled(@PathVariable String id, @RequestBody EnabledRequest req) {
        var user = requireManageable(id);
        keycloak.setEnabled(id, req.enabled());
        audit.record(AuditService.UPDATE, "ORG_USER", user.username(),
                String.valueOf(user.enabled()), String.valueOf(req.enabled()));
        return OrgUserView.of(keycloak.getUser(id), null, true);
    }

    @PostMapping("/{id}/reset-password")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','COMPANY_ADMIN','BRANCH_ADMIN')")
    public OrgUserView resetPassword(@PathVariable String id) {
        UserDirectory.OrgUser user = requireManageable(id);
        String password = randomPassword();
        // Политику пароля проверяет сам справочник и отвечает понятной 422 — отдельный перехват
        // ошибки внешней службы больше не нужен (учётные записи в нашей базе).
        keycloak.resetPassword(id, password);
        audit.record(AuditService.UPDATE, "ORG_USER", user.username(), null, "reset-password");
        return OrgUserView.of(user, password, true);
    }

    @PatchMapping("/{id}/role")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','COMPANY_ADMIN','BRANCH_ADMIN')")
    public OrgUserView setRole(@PathVariable String id, @RequestBody CreateRequest req) {
        var user = requireManageable(id);
        String role = normalizeRole(req.role());
        keycloak.setSingleRealmRole(id, role, ALL_REVOCABLE);
        audit.record(AuditService.UPDATE, "ORG_USER", user.username(), null, "role → " + role);
        return OrgUserView.of(keycloak.getUser(id), null, true);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','COMPANY_ADMIN','BRANCH_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        var user = requireManageable(id);
        keycloak.deleteUser(id);
        audit.record(AuditService.DELETE, "ORG_USER", user.username(), null, null);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------

    /**
     * Разрешённая роль с учётом уровня вызывающего: BRANCH_ADMIN — админ компании/системы;
     * COMPANY_ADMIN — ТОЛЬКО системный администратор (выдача первого входа новой
     * организации, роль «реестродержателя» на время, пока не подключена единая платформа
     * Минтранса — см. UNIFIED_PLATFORM_MODE=stub, spec/notes/05 §5.2). Сознательно не
     * разрешаем COMPANY_ADMIN выдавать COMPANY_ADMIN самому себе/другим — самостоятельное
     * размножение администраторов компании не то же самое, что первичное провижининг платформой.
     */
    /**
     * Контрагенты внешнего пользователя: для ролей кабинета накладных обязателен хотя бы один
     * идентификатор клиента (иначе кабинет будет пустым), для остальных ролей список игнорируется.
     */
    private static List<String> normalizeClientIds(String role, List<String> raw) {
        if (!CLIENT_ROLES.contains(role)) {
            return List.of();
        }
        List<String> ids = raw == null ? List.of()
                : raw.stream().filter(s -> s != null && !s.isBlank()).map(String::trim).distinct().toList();
        if (ids.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Для роли кабинета накладных укажите хотя бы одного клиента (clientIds)");
        }
        for (String id : ids) {
            try {
                java.util.UUID.fromString(id);
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Идентификатор клиента не распознан: " + id);
            }
        }
        return ids;
    }

    private String normalizeRole(String raw) {
        String role = raw == null ? "" : raw.trim().toUpperCase();
        if (GRANTABLE.contains(role)) {
            return role;
        }
        // Логины контрагентам выдаёт перевозчик (у них скоуп по клиентам, а не по организации).
        if (CLIENT_ROLES.contains(role)
                && (currentUser.hasRole("SYSTEM_ADMIN") || currentUser.hasRole("COMPANY_ADMIN"))) {
            return role;
        }
        // Таможенник — государственная роль, выдаёт только системный администратор.
        if (CUSTOMS_OFFICER.equals(role) && currentUser.hasRole("SYSTEM_ADMIN")) {
            return role;
        }
        if (BRANCH_ADMIN.equals(role)
                && (currentUser.hasRole("SYSTEM_ADMIN") || currentUser.hasRole("COMPANY_ADMIN"))) {
            return role;
        }
        if (COMPANY_ADMIN.equals(role) && currentUser.hasRole("SYSTEM_ADMIN")) {
            return role;
        }
        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Недопустимая роль: " + raw + ". Разрешено: " + ALL_REVOCABLE
                        + (currentUser.hasRole("SYSTEM_ADMIN") ? ", COMPANY_ADMIN" : ""));
    }

    /** Область для выборки: тенант — своя (компания+филиалы); платформа — запрошенная или все свои. */
    private Iterable<String> scopeFor(String requestedOrg) {
        if (tenantScope.isBounded()) {
            return tenantScope.rmas();
        }
        return requestedOrg == null || requestedOrg.isBlank()
                ? List.of()
                : List.of(requestedOrg.trim());
    }

    private void requireWritable(String organizationRma) {
        if (tenantScope.isBounded() && !tenantScope.canWrite(organizationRma)) {
            throw new AccessDeniedException("Выдать доступ можно только в свою организацию");
        }
    }

    private UserDirectory.OrgUser requireInScope(String userId) {
        var user = keycloak.getUser(userId);
        if (tenantScope.isBounded()
                && (user.organizationRma() == null || !tenantScope.contains(user.organizationRma()))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Пользователь не найден");
        }
        return user;
    }

    /**
     * Учётная запись в области вызывающего И по его уровню. Раньше хватало совпадения
     * организации: администратор компании мог сбросить пароль любой учётке с тем же
     * organization_rma — в том числе государственной (инспектор, заведённый в организацию)
     * или равному себе администратору компании — и войти под ней временным паролем.
     */
    private UserDirectory.OrgUser requireManageable(String userId) {
        var user = requireInScope(userId);
        if (isSelf(user)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Свою учётную запись здесь менять нельзя: пароль меняется на странице «Сменить пароль», "
                            + "блокировку, роль и удаление выполняет вышестоящий администратор");
        }
        if (!canManage(user)) {
            throw new AccessDeniedException("Эта учётная запись вне ваших полномочий");
        }
        return user;
    }

    /**
     * Вправе ли вызывающий управлять учёткой: администратор платформы — любой (кроме своей);
     * администратор компании — только ролями, которые сам выдаёт (операционные, кабинеты
     * контрагентов, администратор филиала); администратор филиала — только операционными.
     */
    boolean canManage(UserDirectory.OrgUser user) {
        if (isSelf(user)) {
            return false;
        }
        if (!tenantScope.isBounded()) {
            return true;
        }
        Set<String> allowed = new java.util.HashSet<>(GRANTABLE);
        if (currentUser.hasRole("COMPANY_ADMIN")) {
            allowed.addAll(CLIENT_ROLES);
            allowed.add(BRANCH_ADMIN);
        }
        return user.roles() == null || allowed.containsAll(user.roles());
    }

    private boolean isSelf(UserDirectory.OrgUser user) {
        return currentUser.subject().map(s -> s.equals(user.id())).orElse(false);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    // Длина 16 — с запасом над минимумом парольной политики реалма (length(12), см.
    // infra/keycloak/epd-realm.json); 10 символов не проходили политику и роняли
    // create()/resetPassword() в 500 (найдено приёмочным тестированием 2026-09-04).
    private static String randomPassword() {
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            sb.append(PWD_ALPHABET[RANDOM.nextInt(PWD_ALPHABET.length)]);
        }
        return sb.toString();
    }
}
