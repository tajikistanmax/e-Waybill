package tj.mintrans.epd.waybill.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tj.mintrans.epd.waybill.domain.Notification;
import tj.mintrans.epd.waybill.service.NotificationService;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Уведомления текущей организации: список, счётчик непрочитанных, отметки о прочтении.
 */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping
    public List<Notification> list() {
        return service.list();
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unreadCount() {
        return Map.of("count", service.unreadCount());
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<Void> read(@PathVariable UUID id) {
        service.markRead(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/read-all")
    public ResponseEntity<Void> readAll() {
        service.markAllRead();
        return ResponseEntity.noContent().build();
    }

    /** Тело сообщения водителя диспетчеру («Сообщить о проблеме»). */
    public record DriverIssueRequest(String issueType, @NotBlank String message, UUID waybillId) {
    }

    /**
     * Водитель/механик сообщает о проблеме — уведомление уходит диспетчеру/админу его организации.
     * Отдельный канал «рабочее место → диспетчерская» (не событие путевого листа).
     */
    @PostMapping("/report")
    @PreAuthorize("hasAnyRole('DRIVER','MECHANIC')")
    public ResponseEntity<Void> report(@Valid @RequestBody DriverIssueRequest req) {
        service.reportDriverIssue(req.issueType(), req.message(), req.waybillId());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }
}
