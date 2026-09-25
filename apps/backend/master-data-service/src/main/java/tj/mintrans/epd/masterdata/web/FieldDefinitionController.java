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
import org.springframework.web.server.ResponseStatusException;
import tj.mintrans.epd.masterdata.domain.FieldDefinition;
import tj.mintrans.epd.masterdata.repository.FieldDefinitionRepository;
import tj.mintrans.epd.masterdata.service.AuditService;
import tj.mintrans.epd.masterdata.web.error.NotFoundException;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

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

    /** Вид ПЛ — код классификатора WAYBILL_TYPE (WB_BUS, WB_TRUCK_INTL…). */
    private static final Pattern WAYBILL_TYPE = Pattern.compile("^WB_[A-Z_]{2,30}$");
    /**
     * Ключ поля — имя в JSON документа ({@code typeData.custom.<ключ>}): латиница, цифры и «_»,
     * с буквы. Раньше принимался любой текст («моё поле», пробелы) — такой ключ ломал выгрузки.
     */
    private static final Pattern FIELD_KEY = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{1,39}$");
    static final Set<String> DATA_TYPES = Set.of("STRING", "NUMBER", "DATE", "BOOLEAN", "ENUM");

    @PostMapping
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<FieldDefinition> upsert(@Valid @RequestBody FieldDefinitionRequest req) {
        String waybillType = req.waybillType().trim();
        String fieldKey = req.fieldKey().trim();
        String dataType = req.dataType().trim().toUpperCase(Locale.ROOT);
        if (!WAYBILL_TYPE.matcher(waybillType).matches()) {
            throw unprocessable("Неизвестный вид путевого листа: " + waybillType);
        }
        if (!FIELD_KEY.matcher(fieldKey).matches()) {
            throw unprocessable("Ключ поля — латинские буквы, цифры и «_», начинается с буквы, 2–40 символов "
                    + "(например cargoWeight)");
        }
        if (!DATA_TYPES.contains(dataType)) {
            throw unprocessable("Тип данных — один из: " + String.join(", ", DATA_TYPES.stream().sorted().toList()));
        }
        String labelRu = req.labelRu().trim();
        String labelTj = req.labelTj() == null || req.labelTj().isBlank() ? null : req.labelTj().trim();
        if (labelRu.length() > 120 || (labelTj != null && labelTj.length() > 120)) {
            throw unprocessable("Название поля — не длиннее 120 символов");
        }
        String options = null;
        if ("ENUM".equals(dataType)) {
            List<String> values = splitOptions(req.options());
            if (values.isEmpty()) {
                throw unprocessable("Для списка укажите варианты через запятую");
            }
            if (values.size() != new java.util.HashSet<>(values).size()) {
                throw unprocessable("Варианты списка повторяются");
            }
            options = String.join(", ", values);
        }
        if (req.sortOrder() != null && (req.sortOrder() < 0 || req.sortOrder() > 999)) {
            throw unprocessable("Порядок — от 0 до 999");
        }
        var existing = repository.findByWaybillTypeAndFieldKey(waybillType, fieldKey);
        String oldValue = existing.map(FieldDefinition::getLabelRu).orElse(null); // до мутации
        var f = existing.orElseGet(FieldDefinition::new);
        f.setWaybillType(waybillType);
        f.setFieldKey(fieldKey);
        f.setLabelRu(labelRu);
        f.setLabelTj(labelTj);
        f.setDataType(dataType);
        f.setRequired(req.required() != null && req.required());
        f.setOptions(options);
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

    /** Варианты списка: через запятую, без пустых, с обрезанными пробелами. */
    static List<String> splitOptions(String raw) {
        if (raw == null) {
            return List.of();
        }
        return java.util.Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static ResponseStatusException unprocessable(String message) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
