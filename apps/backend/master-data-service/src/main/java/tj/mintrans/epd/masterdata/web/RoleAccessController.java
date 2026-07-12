package tj.mintrans.epd.masterdata.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tj.mintrans.epd.masterdata.config.CurrentUser;
import tj.mintrans.epd.masterdata.domain.RoleAccess;
import tj.mintrans.epd.masterdata.repository.RoleAccessRepository;
import tj.mintrans.epd.masterdata.service.AuditService;
import tj.mintrans.epd.masterdata.web.error.NotFoundException;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Доступ ролей к разделам меню (§29). Чтение — любому авторизованному (фронт строит навигацию);
 * изменение — SYSTEM_ADMIN, с аудитом. Это UI-навигация, а не безопасность: реальные права
 * проверяет @PreAuthorize по ролям Keycloak (их из UI не поменять).
 */
@RestController
@RequestMapping("/api/v1/role-access")
public class RoleAccessController {

    /** Известные разделы меню (соответствуют NavKey на фронте). */
    private static final Set<String> KNOWN_NAV = Set.of(
            "dashboard", "waybills", "dispatcher", "med", "tech", "driver", "inspector",
            "company", "fleet", "monitoring", "registry", "violations", "reports", "dictionaries", "settings");

    private final RoleAccessRepository repository;
    private final AuditService audit;
    private final CurrentUser currentUser;

    public RoleAccessController(RoleAccessRepository repository, AuditService audit, CurrentUser currentUser) {
        this.repository = repository;
        this.audit = audit;
        this.currentUser = currentUser;
    }

    public record RoleAccessView(String role, String homeKey, List<String> navKeys) {
        static RoleAccessView of(RoleAccess ra) {
            var keys = Arrays.stream(ra.getNavKeys().split("\\s*,\\s*")).filter(s -> !s.isBlank()).toList();
            return new RoleAccessView(ra.getRole(), ra.getHomeKey(), keys);
        }
    }

    public record RoleAccessUpdate(@NotBlank String role, @NotBlank String homeKey, List<String> navKeys) {
    }

    @GetMapping
    public List<RoleAccessView> list() {
        return repository.findAll().stream()
                .sorted((a, b) -> a.getRole().compareTo(b.getRole()))
                .map(RoleAccessView::of)
                .toList();
    }

    @PostMapping
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public RoleAccessView update(@Valid @RequestBody RoleAccessUpdate req) {
        // Роли заводятся миграцией (набор ролей Keycloak фиксирован) — произвольные через API не создаём.
        var ra = repository.findById(req.role())
                .orElseThrow(() -> new NotFoundException("Роль %s не найдена".formatted(req.role())));

        if (!KNOWN_NAV.contains(req.homeKey())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Неизвестный стартовый раздел: " + req.homeKey());
        }
        var nav = new LinkedHashSet<String>();
        if (req.navKeys() != null) {
            for (String k : req.navKeys()) {
                if (!KNOWN_NAV.contains(k)) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Неизвестный раздел: " + k);
                }
                nav.add(k);
            }
        }
        // Защита от само-локаута: у SYSTEM_ADMIN всегда остаётся доступ к «Настройкам»
        // (иначе, сняв его, администратор потерял бы возможность вернуть настройки).
        if ("SYSTEM_ADMIN".equals(req.role())) {
            nav.add("settings");
        }
        if (nav.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "У роли должен быть хотя бы один раздел");
        }
        // Стартовый раздел должен входить в доступные — иначе роль попадёт на скрытую от неё страницу.
        if (!nav.contains(req.homeKey())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Стартовый раздел «%s» не входит в доступные роли".formatted(req.homeKey()));
        }

        String oldValue = ra.getHomeKey() + " | " + ra.getNavKeys();
        ra.setHomeKey(req.homeKey());
        ra.setNavKeys(String.join(",", nav));
        ra.setUpdatedBy(currentUser.username().orElse(null));
        repository.save(ra);
        audit.record(AuditService.UPDATE, "ROLE_ACCESS", req.role(), oldValue, ra.getHomeKey() + " | " + ra.getNavKeys());
        return RoleAccessView.of(ra);
    }
}
