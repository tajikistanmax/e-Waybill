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
import tj.mintrans.epd.masterdata.domain.FieldDefinition;
import tj.mintrans.epd.masterdata.repository.FieldDefinitionRepository;
import tj.mintrans.epd.masterdata.service.AuditService;
import tj.mintrans.epd.masterdata.web.error.NotFoundException;

import java.util.List;
import java.util.UUID;

/**
 * Конструктор дополнительных полей типов путевых листов. Чтение — любому авторизованному
 * (нужно при построении форм ПЛ); изменение — только администратору Минтранса.
 */
@RestController
@RequestMapping("/api/v1/field-definitions")
public class FieldDefinitionController {

    private final FieldDefinitionRepository repository;
    private final AuditService audit;

    public FieldDefinitionController(FieldDefinitionRepository repository, AuditService audit) {
        this.repository = repository;
        this.audit = audit;
    }

    public record FieldDefinitionRequest(
            @NotBlank String waybillType,
            @NotBlank String fieldKey,
            @NotBlank String labelRu,
            String labelTj,
            @NotBlank String dataType,
            Boolean required,
            String options,
            Short sortOrder,
            Boolean active) {
    }

    /** Список полей типа ПЛ; по умолчанию только активные (all=true — включая скрытые). */
    @GetMapping
    public List<FieldDefinition> list(@RequestParam String waybillType,
                                      @RequestParam(defaultValue = "false") boolean all) {
        return all
                ? repository.findByWaybillTypeOrderBySortOrderAscFieldKeyAsc(waybillType)
                : repository.findByWaybillTypeAndActiveTrueOrderBySortOrderAscFieldKeyAsc(waybillType);
    }

    @PostMapping
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<FieldDefinition> upsert(@Valid @RequestBody FieldDefinitionRequest req) {
        var existing = repository.findByWaybillTypeAndFieldKey(req.waybillType(), req.fieldKey());
        String oldValue = existing.map(FieldDefinition::getLabelRu).orElse(null); // до мутации
        var f = existing.orElseGet(FieldDefinition::new);
        f.setWaybillType(req.waybillType());
        f.setFieldKey(req.fieldKey());
        f.setLabelRu(req.labelRu());
        f.setLabelTj(req.labelTj());
        f.setDataType(req.dataType());
        f.setRequired(req.required() != null && req.required());
        f.setOptions(req.options());
        if (req.sortOrder() != null) f.setSortOrder(req.sortOrder());
        f.setActive(req.active() == null || req.active());
        var saved = repository.save(f);
        audit.record(existing.isPresent() ? AuditService.UPDATE : AuditService.CREATE,
                "FIELD_DEFINITION", req.waybillType() + ":" + req.fieldKey(), oldValue, saved.getLabelRu());
        return ResponseEntity.status(existing.isPresent() ? HttpStatus.OK : HttpStatus.CREATED).body(saved);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        var f = repository.findById(id).orElseThrow(() -> new NotFoundException("Определение поля не найдено"));
        repository.delete(f);
        audit.record(AuditService.DELETE, "FIELD_DEFINITION", f.getWaybillType() + ":" + f.getFieldKey(), f.getLabelRu(), null);
        return ResponseEntity.noContent().build();
    }
}
