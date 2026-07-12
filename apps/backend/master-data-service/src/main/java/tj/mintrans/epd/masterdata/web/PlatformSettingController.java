package tj.mintrans.epd.masterdata.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tj.mintrans.epd.masterdata.config.CurrentUser;
import tj.mintrans.epd.masterdata.domain.PlatformSetting;
import tj.mintrans.epd.masterdata.repository.PlatformSettingRepository;
import tj.mintrans.epd.masterdata.service.AuditService;
import tj.mintrans.epd.masterdata.web.error.NotFoundException;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Настройки платформы (§29 ТЗ). Чтение по категории — любому авторизованному; изменение —
 * SYSTEM_ADMIN (с записью в аудит). Публичные категории (контакты) доступны без токена —
 * их читает страница входа.
 */
@RestController
@RequestMapping("/api/v1/settings")
public class PlatformSettingController {

    /** Категории, видимые без аутентификации (страница входа): контакты + язык по умолчанию. */
    private static final List<String> PUBLIC_CATEGORIES = List.of("general", "interface");

    private final PlatformSettingRepository repository;
    private final AuditService audit;
    private final CurrentUser currentUser;

    public PlatformSettingController(PlatformSettingRepository repository, AuditService audit,
                                     CurrentUser currentUser) {
        this.repository = repository;
        this.audit = audit;
        this.currentUser = currentUser;
    }

    public record SettingUpdate(@NotBlank String category, @NotBlank String settingKey, String value) {
    }

    /** Настройки одной категории (для соответствующей страницы раздела «Настройки»). */
    @GetMapping
    public List<PlatformSetting> list(@RequestParam String category) {
        return repository.findByCategoryOrderBySortOrderAscSettingKeyAsc(category);
    }

    /** Публичные настройки (контакты поддержки) — без токена, для страницы входа/помощи. */
    @GetMapping("/public")
    public List<PlatformSetting> publicSettings() {
        return repository.findByCategoryInOrderByCategoryAscSortOrderAsc(PUBLIC_CATEGORIES);
    }

    /**
     * Изменение значения существующей настройки. Ключи задаются миграцией (не создаём
     * произвольные через API) → неизвестная пара category/key = 404. Значение валидируется
     * по типу (BOOLEAN/NUMBER/ENUM), чтобы в БД не попал мусор.
     */
    @PostMapping
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public PlatformSetting update(@Valid @RequestBody SettingUpdate req) {
        var setting = repository.findByCategoryAndSettingKey(req.category(), req.settingKey())
                .orElseThrow(() -> new NotFoundException(
                        "Настройка %s/%s не найдена".formatted(req.category(), req.settingKey())));
        String value = req.value() == null ? "" : req.value().trim();
        validate(setting, value);
        String oldValue = setting.getSettingValue();
        setting.setSettingValue(value);
        setting.setUpdatedBy(currentUser.username().orElse(null));
        setting.setUpdatedAt(OffsetDateTime.now());
        var saved = repository.save(setting);
        audit.record(AuditService.UPDATE, "PLATFORM_SETTING",
                req.category() + ":" + req.settingKey(), oldValue, value);
        return saved;
    }

    /** Проверка значения по типу настройки: BOOLEAN ∈ {true,false}, NUMBER — число, ENUM ∈ options. */
    private void validate(PlatformSetting setting, String value) {
        switch (setting.getValueType()) {
            case "BOOLEAN" -> {
                if (!value.equals("true") && !value.equals("false")) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Значение должно быть true или false");
                }
            }
            case "NUMBER" -> {
                try {
                    Double.parseDouble(value);
                } catch (NumberFormatException e) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Значение должно быть числом");
                }
            }
            case "ENUM" -> {
                var allowed = setting.getOptions() == null ? List.<String>of()
                        : List.of(setting.getOptions().split("\\s*,\\s*"));
                if (!allowed.contains(value)) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Допустимые значения: " + setting.getOptions());
                }
            }
            default -> { /* STRING — без ограничений */ }
        }
    }
}
