package tj.mintrans.epd.waybill.web;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
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
import tj.mintrans.epd.waybill.config.CurrentUser;
import tj.mintrans.epd.waybill.domain.WaybillAttachment;
import tj.mintrans.epd.waybill.repository.WaybillAttachmentRepository;
import tj.mintrans.epd.waybill.service.WaybillService;
import tj.mintrans.epd.waybill.web.error.ApiErrors.NotFoundException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Вложения к путевому листу: скан-копии сопроводительных документов рейса
 * (CMR / международная накладная, ТТН, упаковочный лист, весовой сертификат,
 * скан дозвола E-PERMIT, фото груза). Файлы в БД (bytea).
 *
 * <p>Область видимости — как у чтения ПЛ ({@code WaybillService.get} применяет
 * мультиарендность): тенант видит и правит вложения только своих листов.</p>
 */
@RestController
@RequestMapping("/api/v1/waybills/{id}/attachments")
public class WaybillAttachmentController {

    private static final long MAX_BYTES = 12_000_000;
    private static final int MAX_PER_WAYBILL = 30;
    private static final Set<String> DOC_TYPES = Set.of(
            "CMR", "INVOICE", "PACKING_LIST", "WEIGHT_CERT", "PERMIT_SCAN", "CARGO_PHOTO", "DEFECT_PHOTO", "OTHER");
    private static final Set<String> ALLOWED_TYPES = Set.of(
            "application/pdf", "image/jpeg", "image/png", "image/tiff", "image/heic",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    private final WaybillAttachmentRepository attachments;
    private final WaybillService waybills;
    private final CurrentUser currentUser;

    public WaybillAttachmentController(WaybillAttachmentRepository attachments, WaybillService waybills,
                                       CurrentUser currentUser) {
        this.attachments = attachments;
        this.waybills = waybills;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<WaybillAttachmentRepository.Meta> list(@PathVariable UUID id) {
        waybills.get(id); // область видимости + 404
        return attachments.findByWaybillIdOrderByUploadedAtDesc(id);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('DISPATCHER','COMPANY_ADMIN','SYSTEM_ADMIN','ACCOUNTANT','MECHANIC')")
    public ResponseEntity<WaybillAttachmentRepository.Meta> upload(
            @PathVariable UUID id,
            @RequestParam("file") MultipartFile file,
            @RequestParam("docType") String docType,
            @RequestParam(value = "title", required = false) String title) {
        waybills.get(id);
        if (!DOC_TYPES.contains(docType)) {
            throw unprocessable("Неизвестный вид вложения «%s» (допустимо: %s)".formatted(docType, DOC_TYPES));
        }
        if (file == null || file.isEmpty()) {
            throw unprocessable("Файл не передан");
        }
        if (file.getSize() > MAX_BYTES) {
            throw unprocessable("Файл больше %d МБ".formatted(MAX_BYTES / 1_000_000));
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_TYPES.contains(contentType)) {
            throw unprocessable("Недопустимый тип файла: " + contentType + " (нужен PDF, изображение или Word)");
        }
        if (attachments.countByWaybillId(id) >= MAX_PER_WAYBILL) {
            throw unprocessable("Достигнут предел числа вложений путевого листа (%d)".formatted(MAX_PER_WAYBILL));
        }

        var a = new WaybillAttachment();
        a.setWaybillId(id);
        a.setDocType(docType);
        a.setTitle(title == null || title.isBlank() ? null : title.trim());
        a.setFileName(safeName(file.getOriginalFilename()));
        a.setContentType(contentType);
        a.setSizeBytes(file.getSize());
        try {
            a.setData(file.getBytes());
        } catch (IOException e) {
            throw unprocessable("Не удалось прочитать файл");
        }
        a.setUploadedBy(currentUser.username().orElse(null));
        attachments.save(a);
        return ResponseEntity.status(HttpStatus.CREATED).body(
                attachments.findByWaybillIdOrderByUploadedAtDesc(id).stream()
                        .filter(m -> m.getId().equals(a.getId())).findFirst().orElseThrow());
    }

    @GetMapping("/{attachmentId}")
    public ResponseEntity<byte[]> download(@PathVariable UUID id, @PathVariable UUID attachmentId) {
        waybills.get(id);
        var a = attachments.findById(attachmentId)
                .filter(x -> x.getWaybillId().equals(id))
                .orElseThrow(() -> new NotFoundException("Вложение не найдено"));
        ContentDisposition cd = ContentDisposition.attachment()
                .filename(a.getFileName(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .contentType(MediaType.parseMediaType(a.getContentType()))
                .body(a.getData());
    }

    @DeleteMapping("/{attachmentId}")
    @PreAuthorize("hasAnyRole('DISPATCHER','COMPANY_ADMIN','SYSTEM_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable UUID id, @PathVariable UUID attachmentId) {
        waybills.get(id);
        var a = attachments.findById(attachmentId)
                .filter(x -> x.getWaybillId().equals(id))
                .orElseThrow(() -> new NotFoundException("Вложение не найдено"));
        attachments.delete(a);
        return ResponseEntity.noContent().build();
    }

    private static ResponseStatusException unprocessable(String message) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }

    private static String safeName(String name) {
        if (name == null || name.isBlank()) {
            return "attachment";
        }
        String trimmed = name.replace("\\", "/");
        trimmed = trimmed.substring(trimmed.lastIndexOf('/') + 1);
        return trimmed.length() > 255 ? trimmed.substring(trimmed.length() - 255) : trimmed;
    }
}
