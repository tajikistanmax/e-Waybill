package tj.mintrans.epd.masterdata.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tj.mintrans.epd.masterdata.domain.Classifier;
import tj.mintrans.epd.masterdata.repository.ClassifierRepository;
import tj.mintrans.epd.masterdata.service.AuditService;
import tj.mintrans.epd.masterdata.web.error.NotFoundException;

import java.util.List;
import java.util.UUID;

/**
 * Классификаторы (страны, классы ADR, виды дозволов). Чтение — любому авторизованному
 * (нужны в формах международных ПЛ); изменение — только администратору Минтранса.
 */
@RestController
@RequestMapping("/api/v1/classifiers")
public class ClassifierController {

    private final ClassifierRepository repository;
    private final AuditService audit;

    public ClassifierController(ClassifierRepository repository, AuditService audit) {
        this.repository = repository;
        this.audit = audit;
    }

    public record ClassifierRequest(
            @NotBlank String category,
            @NotBlank String code,
            @NotBlank String nameRu,
            String nameTj,
            Short sortOrder,
            Boolean active) {
    }

    /** Список по категории; по умолчанию только активные (all=true — включая скрытые). */
    @GetMapping
    public List<Classifier> list(@RequestParam String category,
                                 @RequestParam(defaultValue = "false") boolean all) {
        return all
                ? repository.findByCategoryOrderBySortOrderAscCodeAsc(category)
                : repository.findByCategoryAndActiveTrueOrderBySortOrderAscCodeAsc(category);
    }

    @PostMapping
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<Classifier> upsert(@Valid @RequestBody ClassifierRequest req) {
        var existing = repository.findByCategoryAndCode(req.category(), req.code());
        String oldValue = existing.map(Classifier::getNameRu).orElse(null); // до мутации
        var c = existing.orElseGet(Classifier::new);
        c.setCategory(req.category());
        c.setCode(req.code());
        c.setNameRu(req.nameRu());
        c.setNameTj(req.nameTj());
        if (req.sortOrder() != null) c.setSortOrder(req.sortOrder());
        c.setActive(req.active() == null || req.active());
        var saved = repository.save(c);
        audit.record(existing.isPresent() ? AuditService.UPDATE : AuditService.CREATE,
                "CLASSIFIER", req.category() + ":" + req.code(), oldValue, saved.getNameRu());
        return ResponseEntity.status(existing.isPresent() ? HttpStatus.OK : HttpStatus.CREATED).body(saved);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        var c = repository.findById(id).orElseThrow(() -> new NotFoundException("Элемент классификатора не найден"));
        repository.delete(c);
        audit.record(AuditService.DELETE, "CLASSIFIER", c.getCategory() + ":" + c.getCode(), c.getNameRu(), null);
        return ResponseEntity.noContent().build();
    }
}
