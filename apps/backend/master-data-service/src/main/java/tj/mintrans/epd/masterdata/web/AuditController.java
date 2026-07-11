package tj.mintrans.epd.masterdata.web;

import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tj.mintrans.epd.masterdata.domain.AuditLog;
import tj.mintrans.epd.masterdata.repository.AuditLogRepository;

import java.util.List;

/**
 * Журнал аудита (только администратор Минтранса). Записи неизменяемы —
 * доступно лишь чтение, последние сверху, с необязательным фильтром по типу сущности.
 */
@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

    private final AuditLogRepository repository;

    public AuditController(AuditLogRepository repository) {
        this.repository = repository;
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
}
