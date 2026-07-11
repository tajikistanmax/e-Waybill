package tj.mintrans.epd.masterdata.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tj.mintrans.epd.masterdata.config.CurrentUser;
import tj.mintrans.epd.masterdata.domain.Policy;
import tj.mintrans.epd.masterdata.repository.PolicyRepository;
import tj.mintrans.epd.masterdata.service.AuditService;
import tj.mintrans.epd.masterdata.service.PolicyResolver;
import tj.mintrans.epd.masterdata.web.error.NotFoundException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Движок бизнес-правил (политик) — административная подсистема «Настройки».
 * Национальные политики и политики типов ПЛ меняет администратор Минтранса (SYSTEM_ADMIN);
 * политики уровня организации — SYSTEM_ADMIN или COMPANY_ADMIN своей организации.
 */
@RestController
@RequestMapping("/api/v1/policies")
public class PolicyController {

    private final PolicyRepository policies;
    private final PolicyResolver resolver;
    private final CurrentUser currentUser;
    private final AuditService audit;

    public PolicyController(PolicyRepository policies, PolicyResolver resolver,
                            CurrentUser currentUser, AuditService audit) {
        this.policies = policies;
        this.resolver = resolver;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    /** Человекочитаемый ключ правила для аудита: «УРОВЕНЬ[:ключ] / правило». */
    private static String auditKey(String scopeLevel, String scopeKey, String ruleKey) {
        return scopeKey == null || scopeKey.isBlank()
                ? "%s / %s".formatted(scopeLevel, ruleKey)
                : "%s:%s / %s".formatted(scopeLevel, scopeKey, ruleKey);
    }

    public record PolicyRequest(
            @NotBlank @Pattern(regexp = "NATIONAL|ORGANIZATION|VEHICLE_TYPE",
                    message = "Уровень: NATIONAL | ORGANIZATION | VEHICLE_TYPE") String scopeLevel,
            String scopeKey,
            @NotBlank String ruleKey,
            @NotBlank String ruleValue,
            Boolean enabled) {
    }

    /** Эффективные правила для (организация, тип ПЛ) — используется waybill-service. */
    @GetMapping("/effective")
    public Map<String, String> effective(@RequestParam(required = false) String organizationRma,
                                         @RequestParam(required = false) String waybillType) {
        // Тенант получает эффективные правила только СВОЕЙ организации (иначе перебором
        // organizationRma можно подсмотреть ORGANIZATION-переопределения чужой орг).
        // Платформа и сервисный аккаунт (waybill-service) — по запрошенному параметру.
        String org = currentUser.isTenantScoped()
                ? currentUser.organizationRma().orElse(null)
                : organizationRma;
        return resolver.effective(org, waybillType);
    }

    /** Список политик. Не-админ видит национальные, по типам ПЛ и политики своей организации. */
    @GetMapping
    public List<Policy> list() {
        var all = policies.findAll();
        if (currentUser.isTenantScoped()) {
            String ownRma = currentUser.organizationRma().orElse(null);
            return all.stream()
                    .filter(p -> !"ORGANIZATION".equals(p.getScopeLevel()) || p.getScopeKey().equals(ownRma))
                    .toList();
        }
        return all;
    }

    @PostMapping
    public ResponseEntity<Policy> upsert(@Valid @RequestBody PolicyRequest req) {
        // NATIONAL всегда с пустым ключом; для остальных ключ обязателен.
        String scopeKey = "NATIONAL".equals(req.scopeLevel())
                ? "" : (req.scopeKey() == null ? "" : req.scopeKey().trim());
        if (!"NATIONAL".equals(req.scopeLevel()) && scopeKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Для уровня %s нужен scopeKey (РМА организации или имя типа ПЛ)".formatted(req.scopeLevel()));
        }
        assertCanWrite(req.scopeLevel(), scopeKey);
        var existing = policies.findByScopeLevelAndScopeKeyAndRuleKey(req.scopeLevel(), scopeKey, req.ruleKey());
        String oldValue = existing.map(Policy::getRuleValue).orElse(null);
        var policy = existing.orElseGet(Policy::new);
        policy.setScopeLevel(req.scopeLevel());
        policy.setScopeKey(scopeKey);
        policy.setRuleKey(req.ruleKey());
        policy.setRuleValue(req.ruleValue());
        policy.setEnabled(req.enabled() == null || req.enabled());
        policy.setUpdatedBy(currentUser.organizationRma().orElse("platform"));
        policy.setUpdatedAt(OffsetDateTime.now());
        var saved = policies.save(policy);
        audit.record(existing.isPresent() ? AuditService.UPDATE : AuditService.CREATE,
                "POLICY", auditKey(req.scopeLevel(), scopeKey, req.ruleKey()), oldValue, req.ruleValue());
        return ResponseEntity.status(existing.isPresent() ? HttpStatus.OK : HttpStatus.CREATED).body(saved);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        var policy = policies.findById(id).orElseThrow(() -> new NotFoundException("Политика не найдена"));
        assertCanWrite(policy.getScopeLevel(), policy.getScopeKey());
        policies.delete(policy);
        audit.record(AuditService.DELETE, "POLICY",
                auditKey(policy.getScopeLevel(), policy.getScopeKey(), policy.getRuleKey()),
                policy.getRuleValue(), null);
        return ResponseEntity.noContent().build();
    }

    /**
     * Права на изменение: национальные политики и политики типов ПЛ — только SYSTEM_ADMIN;
     * политики организации — SYSTEM_ADMIN либо COMPANY_ADMIN этой же организации.
     */
    private void assertCanWrite(String scopeLevel, String scopeKey) {
        if (currentUser.hasRole("SYSTEM_ADMIN")) {
            return;
        }
        if ("ORGANIZATION".equals(scopeLevel)
                && currentUser.hasRole("COMPANY_ADMIN")
                && currentUser.organizationRma().map(rma -> rma.equals(scopeKey)).orElse(false)) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Недостаточно прав для изменения этой политики");
    }
}
