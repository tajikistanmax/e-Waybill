package tj.mintrans.epd.masterdata.web;

import jakarta.validation.constraints.NotBlank;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tj.mintrans.epd.masterdata.domain.AuditLog;
import tj.mintrans.epd.masterdata.repository.AuditLogRepository;
import tj.mintrans.epd.masterdata.service.AuditService;

import java.util.List;
import java.util.Objects;

/**
 * Журнал аудита (только администратор Минтранса). Записи неизменяемы —
 * доступно лишь чтение, последние сверху, с необязательным фильтром по типу сущности.
 */
@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

    private final AuditLogRepository repository;
    private final AuditService auditService;

    public AuditController(AuditLogRepository repository, AuditService auditService) {
        this.repository = repository;
        this.auditService = auditService;
    }

    public record MedicalAccessRequest(@NotBlank String waybillId, @NotBlank String titleType) {
    }

    /**
     * Фиксация факта доступа к расшифрованным медпоказателям (ИБ-13.1.3) — вызывается
     * waybill-service после успешной проверки роли, с ретранслированным JWT инициатора
     * (см. MasterDataClient.authorize — token relay), поэтому актор здесь — реальный
     * пользователь, а не сервисная учётка. Роль проверяется здесь ЖЕ (defense in depth) —
     * основная проверка уже прошла в waybill-service до вызова этого эндпоинта.
     */
    @PostMapping("/medical-access")
    @PreAuthorize("hasAnyRole('DOCTOR','SYSTEM_ADMIN')")
    public void recordMedicalAccess(@jakarta.validation.Valid @RequestBody MedicalAccessRequest req) {
        auditService.record(AuditService.ACCESS, "MEDICAL_INDICATORS",
                req.waybillId() + ":" + req.titleType(), null, null);
    }

    @GetMapping
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public List<AuditLog> list(@RequestParam(required = false) String entityType,
                               @RequestParam(defaultValue = "100") int limit) {
        var pageable = PageRequest.of(0, Math.min(Math.max(limit, 1), 500));
        var page = (entityType == null || entityType.isBlank())
                ? repository.findAllByOrderByOccurredAtDesc(pageable)
                : repository.findByEntityTypeOrderByOccurredAtDesc(entityType, pageable);
        return page.getContent();
    }

    public record VerifyResult(boolean valid, long checkedCount, Long brokenAtSeq, String detail) {
    }

    /**
     * Пересчитывает всю цепочку хешей и сверяет с сохранённой (ИБ-13.6.3) — детектирует
     * любую модификацию исторических записей в обход приложения (в т.ч. прямым SQL,
     * если бы кто-то обошёл триггер неизменяемости через смену владельца/DROP TRIGGER).
     */
    @GetMapping("/verify")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    @Transactional(readOnly = true)
    public VerifyResult verify() {
        String prevHash = AuditService.GENESIS;
        long checked = 0;
        try (var stream = repository.findAllByOrderBySeqAsc()) {
            for (AuditLog entry : (Iterable<AuditLog>) stream::iterator) {
                String expected = AuditService.computeHash(prevHash, entry);
                if (!Objects.equals(expected, entry.getRecordHash())) {
                    return new VerifyResult(false, checked, entry.getSeq(),
                            "Цепочка нарушена на записи seq=%d (id=%s): ожидался hash %s, сохранён %s"
                                    .formatted(entry.getSeq(), entry.getId(), expected, entry.getRecordHash()));
                }
                prevHash = entry.getRecordHash();
                checked++;
            }
        }
        return new VerifyResult(true, checked, null, "Цепочка целостна, проверено записей: " + checked);
    }
}
