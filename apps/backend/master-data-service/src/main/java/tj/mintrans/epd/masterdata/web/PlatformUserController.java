package tj.mintrans.epd.masterdata.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tj.mintrans.epd.masterdata.auth.IntegratorAccounts;
import tj.mintrans.epd.masterdata.auth.UserDirectory;
import tj.mintrans.epd.masterdata.config.CurrentUser;
import tj.mintrans.epd.masterdata.config.IntegratorChannelFilter;
import tj.mintrans.epd.masterdata.domain.AppUser;
import tj.mintrans.epd.masterdata.repository.AppUserRepository;
import tj.mintrans.epd.masterdata.repository.OrganizationRepository;
import tj.mintrans.epd.masterdata.service.AuditService;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * «Пользователи» — все учётные записи платформы для администратора платформы. Замена страницы
 * старой платформы /admin/user (Backpack UserCrudController: 1094 пользователя, фильтры по роли
 * и компании, создание с любой ролью, блокировка).
 *
 * <p>«Доступы» ({@link OrgUserController}) — по одной организации и только операционные роли; здесь
 * — поиск по всей платформе и учётки без организации (администратор платформы, аналитик,
 * инспектор, таможенник), которых в «Доступах» не видно вовсе. Блокировка, сброс пароля и второго
 * фактора, удаление — общими точками {@code /api/v1/org-users/{id}/…} (администратору платформы
 * они доступны для любой учётки, кроме своей).</p>
 */
@RestController
@RequestMapping("/api/v1/platform-users")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class PlatformUserController {

    /** Роли без привязки к организации (надзор и администрирование платформы). */
    static final Set<String> PLATFORM_ROLES = Set.of("SYSTEM_ADMIN", "MINTRANS_ANALYST", "INSPECTOR", "CUSTOMS_OFFICER");
    /** Роли сотрудников перевозчика — организация обязательна. */
    static final Set<String> ORGANIZATION_ROLES = Set.of("COMPANY_ADMIN", "BRANCH_ADMIN", "DISPATCHER", "DOCTOR",
            "MECHANIC", "DRIVER", "ACCOUNTANT", "FUEL_STATION");
    /** Привилегированные роли: второй фактор обязателен (ИБ-13.2.2). */
    static final Set<String> PRIVILEGED_ROLES = Set.of("SYSTEM_ADMIN", "MINTRANS_ANALYST", "INSPECTOR");
    /** Все роли, которые назначаются здесь (служебная API_INTEGRATOR — только из окружения стенда). */
    private static final Set<String> ASSIGNABLE;
    /** Снимаются при смене роли (плюс кабинеты контрагентов, если учётка была из них). */
    private static final Set<String> REVOCABLE;

    static {
        var s = new java.util.HashSet<>(PLATFORM_ROLES);
        s.addAll(ORGANIZATION_ROLES);
        ASSIGNABLE = Set.copyOf(s);
        s.add("CLIENT_SENDER");
        s.add("CLIENT_FORWARDER");
        REVOCABLE = Set.copyOf(s);
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] PWD_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789".toCharArray();

    private final AppUserRepository users;
    private final UserDirectory directory;
    private final OrganizationRepository organizations;
    private final CurrentUser currentUser;
    private final AuditService audit;
    private final IntegratorAccounts integrators;

    public PlatformUserController(AppUserRepository users, UserDirectory directory, OrganizationRepository organizations,
                                  CurrentUser currentUser, AuditService audit, IntegratorAccounts integrators) {
        this.users = users;
        this.directory = directory;
        this.organizations = organizations;
        this.currentUser = currentUser;
        this.audit = audit;
        this.integrators = integrators;
    }

    /**
     * {@code service} — учётка из настроек стенда (служебная, агрегатор): в интерфейсе без действий.
     * {@code apiChannels} — каналы внешней системы-интегратора (G2), у остальных {@code null}.
     */
    public record PlatformUserView(String id, String username, String firstName, String lastName,
                                   String organizationRma, List<String> roles, boolean enabled, boolean locked,
                                   OffsetDateTime lastLoginAt, OffsetDateTime createdAt, boolean mustChangePassword,
                                   boolean secondFactorRequired, boolean secondFactorEnrolled, boolean self,
                                   boolean service, List<String> apiChannels, String temporaryPassword) {
    }

    public record PageView(List<PlatformUserView> content, long total, int page, int size) {
    }

    /** {@code apiChannels} — только для роли {@code API_INTEGRATOR}: ref, aggregator, gps, neru. */
    public record CreateRequest(@NotBlank String username, String firstName, String lastName, String email,
                                String personRma, String organizationRma, @NotBlank String role,
                                List<String> apiChannels) {
    }

    public record RoleRequest(@NotBlank String role, String organizationRma) {
    }

    public record ChannelsRequest(List<String> apiChannels) {
    }

    /**
     * Поиск: {@code q} — логин, фамилия, имя или РМА; {@code role}; {@code organizationRma} или
     * {@code withoutOrganization=true} (учётки платформы); {@code status}: ACTIVE / BLOCKED / LOCKED.
     */
    @GetMapping
    public PageView list(@RequestParam(required = false) String q,
                         @RequestParam(required = false) String role,
                         @RequestParam(required = false) String organizationRma,
                         @RequestParam(required = false, defaultValue = "false") boolean withoutOrganization,
                         @RequestParam(required = false) String status,
                         @RequestParam(required = false, defaultValue = "0") int page,
                         @RequestParam(required = false, defaultValue = "50") int size) {
        String like = q == null || q.isBlank() ? "" : "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
        String roleLike = role == null || role.isBlank() ? "" : "%," + role.trim().toUpperCase(Locale.ROOT) + ",%";
        String st = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        var pageable = PageRequest.of(Math.max(0, page), Math.min(200, Math.max(1, size)), Sort.by("username"));
        var result = users.search(like, roleLike, organizationRma == null ? "" : organizationRma.trim(),
                withoutOrganization, st, OffsetDateTime.now(), pageable);
        return new PageView(result.getContent().stream().map(u -> view(u, null)).toList(),
                result.getTotalElements(), result.getNumber(), result.getSize());
    }

    /** Создание учётки с любой ролью, в том числе платформенной (без организации). */
    @PostMapping
    public ResponseEntity<PlatformUserView> create(@Valid @RequestBody CreateRequest req) {
        if (INTEGRATOR.equalsIgnoreCase(req.role() == null ? "" : req.role().trim())) {
            return createIntegrator(req);
        }
        String role = normalizeRole(req.role());
        String org = requireOrganizationFor(role, req.organizationRma());
        String password = randomPassword();
        String id = directory.createUser(req.username().trim(), req.firstName(), req.lastName(), req.email(),
                blankToNull(req.personRma()), org, password, List.of());
        directory.setSingleRealmRole(id, role, REVOCABLE);
        if (PRIVILEGED_ROLES.contains(role)) {
            directory.setSecondFactorRequired(id, true);
        }
        audit.record(AuditService.CREATE, "PLATFORM_USER", req.username().trim(), null,
                role + (org == null ? "" : " @ " + org));
        return ResponseEntity.status(HttpStatus.CREATED).body(view(entity(id), password));
    }

    /** Смена роли и организации (перевод сотрудника, назначение аналитиком и т. п.). */
    @PatchMapping("/{id}/role")
    public PlatformUserView changeRole(@PathVariable String id, @Valid @RequestBody RoleRequest req) {
        AppUser user = entity(id);
        if (isSelf(user)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Свою роль менять нельзя — это делает другой администратор платформы");
        }
        if (IntegratorAccounts.isIntegrator(user)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Учётная запись интеграции: роль не меняется, меняются её каналы");
        }
        String role = normalizeRole(req.role());
        String org = requireOrganizationFor(role, req.organizationRma());
        String before = String.join(",", user.roleList()) + (user.getOrganizationRma() == null ? "" : " @ " + user.getOrganizationRma());
        directory.setSingleRealmRole(id, role, REVOCABLE);
        directory.setOrganization(id, org);
        if (PRIVILEGED_ROLES.contains(role)) {
            directory.setSecondFactorRequired(id, true);
        }
        audit.record(AuditService.UPDATE, "PLATFORM_USER", user.getUsername(), before,
                role + (org == null ? "" : " @ " + org));
        return view(entity(id), null);
    }

    /**
     * Учётная запись внешней системы (КВД, Smart City…; сверка 25.09, G2) — как {@code company_for_api}
     * в «Роҳхат»: логин, пароль и каналы, куда ей можно. Пароль показывается один раз; временным он
     * не считается — программа сменить его на странице не может, администратор передаёт его сам.
     */
    private ResponseEntity<PlatformUserView> createIntegrator(CreateRequest req) {
        List<String> channels = normalizeChannels(req.apiChannels());
        String password = randomPassword();
        String id = directory.createUser(req.username().trim(), req.firstName(), req.lastName(), req.email(),
                null, null, password, List.of());
        directory.configureIntegrator(id, channels);
        audit.record(AuditService.CREATE, "PLATFORM_USER", req.username().trim(), null,
                INTEGRATOR + " " + String.join(",", channels));
        return ResponseEntity.status(HttpStatus.CREATED).body(view(entity(id), password));
    }

    /** Каналы внешней системы. Учётки из настроек стенда здесь не правятся. */
    @PatchMapping("/{id}/channels")
    public PlatformUserView changeChannels(@PathVariable String id, @RequestBody ChannelsRequest req) {
        AppUser user = entity(id);
        if (!IntegratorAccounts.isIntegrator(user) || integrators.environmentManaged(user)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Каналы меняются только у внешних систем, заведённых здесь; учётки стенда — в infra/.env");
        }
        List<String> channels = normalizeChannels(req.apiChannels());
        String before = user.getApiChannels();
        directory.configureIntegrator(id, channels);
        audit.record(AuditService.UPDATE, "PLATFORM_USER", user.getUsername(), before, String.join(",", channels));
        return view(entity(id), null);
    }

    // ------------------------------------------------------------------

    static final String INTEGRATOR = "API_INTEGRATOR";

    static List<String> normalizeChannels(List<String> raw) {
        List<String> out = new java.util.ArrayList<>();
        for (String c : raw == null ? List.<String>of() : raw) {
            String n = c == null ? "" : c.trim().toLowerCase(Locale.ROOT);
            if (!IntegratorChannelFilter.CHANNELS.contains(n)) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Неизвестный канал: " + c + ". Допустимы: ref, aggregator, gps, neru");
            }
            if (!out.contains(n)) {
                out.add(n);
            }
        }
        if (out.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Отметьте хотя бы один канал внешней системы");
        }
        return out;
    }

    private String normalizeRole(String raw) {
        String role = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!ASSIGNABLE.contains(role)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Недопустимая роль: " + raw + ". Кабинеты грузоотправителя и экспедитора выдаются в «Доступах» "
                            + "организации (нужен список её клиентов), служебные учётки — настройками стенда.");
        }
        return role;
    }

    /** Для ролей перевозчика — существующая организация; для платформенных — по желанию. */
    private String requireOrganizationFor(String role, String organizationRma) {
        String org = blankToNull(organizationRma);
        if (org == null) {
            if (ORGANIZATION_ROLES.contains(role)) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Для роли сотрудника перевозчика укажите организацию");
            }
            return null;
        }
        if (organizations.findByRma(org).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Организация " + org + " не найдена");
        }
        return org;
    }

    private AppUser entity(String id) {
        try {
            return users.findById(java.util.UUID.fromString(id))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Пользователь не найден"));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Пользователь не найден");
        }
    }

    private boolean isSelf(AppUser user) {
        return currentUser.subject().map(s -> s.equals(user.getId().toString())).orElse(false);
    }

    private PlatformUserView view(AppUser u, String temporaryPassword) {
        boolean locked = u.getLockedUntil() != null && u.getLockedUntil().isAfter(OffsetDateTime.now());
        return new PlatformUserView(u.getId().toString(), u.getUsername(), u.getFirstName(), u.getLastName(),
                u.getOrganizationRma(), u.roleList(), u.isEnabled(), locked, u.getLastLoginAt(), u.getCreatedAt(),
                u.isMustChangePassword(), u.isTotpRequired(), u.getTotpSecret() != null, isSelf(u),
                integrators.environmentManaged(u), IntegratorAccounts.isIntegrator(u) ? u.apiChannelList() : null,
                temporaryPassword);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String randomPassword() {
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            sb.append(PWD_ALPHABET[RANDOM.nextInt(PWD_ALPHABET.length)]);
        }
        return sb.toString();
    }
}
