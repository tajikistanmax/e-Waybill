package tj.mintrans.epd.masterdata.web;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import tj.mintrans.epd.masterdata.config.CurrentUser;
import tj.mintrans.epd.masterdata.domain.BrandingAsset;
import tj.mintrans.epd.masterdata.repository.BrandingAssetRepository;
import tj.mintrans.epd.masterdata.service.AuditService;
import tj.mintrans.epd.masterdata.web.error.NotFoundException;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * Изображения бренда (логотип, фон входа), редактируемые администратором (§29).
 * GET — публичный (страница входа читает без токена; отсутствие = 404 → фолбэк на дефолт на фронте);
 * загрузка/сброс — только SYSTEM_ADMIN, с записью в аудит.
 */
@RestController
@RequestMapping("/api/v1/branding")
public class BrandingController {

    /** Допустимые ключи изображений (фиксированный набор — не произвольные). */
    private static final Set<String> KEYS = Set.of("logo", "login_bg");
    /** Предел размера: логотип/фон входа заведомо меньше (совпадает с spring.servlet.multipart). */
    private static final long MAX_BYTES = 3_000_000;

    private final BrandingAssetRepository repository;
    private final AuditService audit;
    private final CurrentUser currentUser;

    public BrandingController(BrandingAssetRepository repository, AuditService audit, CurrentUser currentUser) {
        this.repository = repository;
        this.audit = audit;
        this.currentUser = currentUser;
    }

    /** Отдать изображение бренда (публично; 404 → фронт показывает зашитый дефолт). */
    @GetMapping("/{key}")
    public ResponseEntity<byte[]> get(@PathVariable String key) {
        var asset = repository.findById(key)
                .orElseThrow(() -> new NotFoundException("Изображение бренда «%s» не задано".formatted(key)));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(asset.getContentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .body(asset.getData());
    }

    /** Загрузить/заменить изображение бренда (только SYSTEM_ADMIN). */
    @PostMapping("/{key}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public Map<String, Object> upload(@PathVariable String key, @RequestParam("file") MultipartFile file) {
        if (!KEYS.contains(key)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Неизвестное изображение бренда «%s» (допустимо: %s)".formatted(key, KEYS));
        }
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Файл не передан");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Файл больше %d МБ".formatted(MAX_BYTES / 1_000_000));
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Ожидается изображение (image/*), получено: " + contentType);
        }
        var asset = repository.findById(key).orElseGet(BrandingAsset::new);
        boolean existed = asset.getData() != null;
        asset.setAssetKey(key);
        asset.setContentType(contentType);
        try {
            asset.setData(file.getBytes());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Не удалось прочитать файл");
        }
        asset.setUpdatedBy(currentUser.username().orElse(null));
        repository.save(asset);
        audit.record(existed ? AuditService.UPDATE : AuditService.CREATE, "BRANDING_ASSET", key,
                existed ? "(изображение)" : null, "%s, %d байт".formatted(contentType, file.getSize()));
        return Map.of("key", key, "contentType", contentType, "size", file.getSize());
    }

    /** Сбросить изображение бренда к зашитому дефолту (удалить строку; только SYSTEM_ADMIN). */
    @DeleteMapping("/{key}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<Void> reset(@PathVariable String key) {
        if (repository.existsById(key)) {
            repository.deleteById(key);
            audit.record(AuditService.DELETE, "BRANDING_ASSET", key, "(изображение)", "сброшено к дефолту");
        }
        return ResponseEntity.noContent().build();
    }
}
