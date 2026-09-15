package tj.mintrans.epd.masterdata.web;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
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
import tj.mintrans.epd.masterdata.domain.OrganizationDocument;
import tj.mintrans.epd.masterdata.repository.OrganizationDocumentRepository;
import tj.mintrans.epd.masterdata.repository.OrganizationRepository;
import tj.mintrans.epd.masterdata.service.AuditService;
import tj.mintrans.epd.masterdata.web.error.NotFoundException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Учредительные и разрешительные документы организации (свидетельство о регистрации,
 * устав, лицензия перевозчика, справка ИНН и т.д.). Скан-копии хранятся в БД (bytea).
 *
 * <p>Мультиарендность: администратор компании ведёт документы только СВОЕЙ организации;
 * сисадмин — любых; аналитик Минтранса — только читает.</p>
 */
@RestController
@RequestMapping("/api/v1/organizations/{rma}/documents")
public class OrganizationDocumentController {

    /** Фиксированный перечень видов документов (значение + подпись — на фронте). */
    private static final Set<String> DOC_TYPES = Set.of(
            "REGISTRATION_CERT", "CHARTER", "CARRIER_LICENSE", "TAX_CERT",
            "DIRECTOR_ORDER", "BANK_DETAILS", "OTHER");
    private static final long MAX_BYTES = 12_000_000;
    private static final Set<String> ALLOWED_TYPES = Set.of(
            "application/pdf", "image/jpeg", "image/png", "image/tiff", "image/heic",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    private static final int MAX_PER_ORG = 50;

    private final OrganizationDocumentRepository documents;
    private final OrganizationRepository organizations;
    private final CurrentUser currentUser;
    private final AuditService audit;

    public OrganizationDocumentController(OrganizationDocumentRepository documents,
                                          OrganizationRepository organizations,
                                          CurrentUser currentUser, AuditService audit) {
        this.documents = documents;
        this.organizations = organizations;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    @GetMapping
    public List<OrganizationDocumentRepository.Meta> list(@PathVariable String rma) {
        requireOrganizationExists(rma);
        requireVisible(rma);
        return documents.findByOrganizationRmaOrderByUploadedAtDesc(rma);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','COMPANY_ADMIN','API_INTEGRATOR')")
    public ResponseEntity<OrganizationDocumentRepository.Meta> upload(
            @PathVariable String rma,
            @RequestParam("file") MultipartFile file,
            @RequestParam("docType") String docType,
            @RequestParam(value = "title", required = false) String title) {
        requireOrganizationExists(rma);
        requireOwn(rma);
        if (!DOC_TYPES.contains(docType)) {
            throw unprocessable("Неизвестный вид документа «%s» (допустимо: %s)".formatted(docType, DOC_TYPES));
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
        if (documents.countByOrganizationRma(rma) >= MAX_PER_ORG) {
            throw unprocessable("Достигнут предел числа документов организации (%d)".formatted(MAX_PER_ORG));
        }

        var doc = new OrganizationDocument();
        doc.setOrganizationRma(rma);
        doc.setDocType(docType);
        doc.setTitle(title == null || title.isBlank() ? null : title.trim());
        doc.setFileName(safeName(file.getOriginalFilename()));
        doc.setContentType(contentType);
        doc.setSizeBytes(file.getSize());
        try {
            doc.setData(file.getBytes());
        } catch (IOException e) {
            throw unprocessable("Не удалось прочитать файл");
        }
        doc.setStatus("PENDING");
        doc.setUploadedBy(currentUser.username().orElse(null));
        documents.save(doc);
        audit.record(AuditService.CREATE, "ORGANIZATION_DOCUMENT", rma, null,
                "%s · %s · %d байт".formatted(docType, doc.getFileName(), file.getSize()));
        return ResponseEntity.status(HttpStatus.CREATED).body(meta(rma, doc.getId()));
    }

    /** Одобрение документа Минтрансом (сверка с реестром юрлиц / лицензий). */
    @PostMapping("/{docId}/approve")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public OrganizationDocumentRepository.Meta approve(@PathVariable String rma, @PathVariable UUID docId,
                                                       @RequestParam(value = "note", required = false) String note) {
        return review(rma, docId, "APPROVED", note);
    }

    @PostMapping("/{docId}/reject")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public OrganizationDocumentRepository.Meta reject(@PathVariable String rma, @PathVariable UUID docId,
                                                      @RequestParam(value = "note", required = false) String note) {
        return review(rma, docId, "REJECTED", note);
    }

    private OrganizationDocumentRepository.Meta review(String rma, UUID docId, String status, String note) {
        requireOrganizationExists(rma);
        var doc = documents.findById(docId)
                .filter(d -> d.getOrganizationRma().equals(rma))
                .orElseThrow(() -> new NotFoundException("Документ не найден"));
        doc.setStatus(status);
        doc.setReviewNote(note == null || note.isBlank() ? null : note.trim());
        doc.setReviewedBy(currentUser.username().orElse(null));
        doc.setReviewedAt(java.time.OffsetDateTime.now());
        documents.save(doc);
        audit.record(AuditService.UPDATE, "ORGANIZATION_DOCUMENT", rma, doc.getFileName(), status);
        return meta(rma, docId);
    }

    private OrganizationDocumentRepository.Meta meta(String rma, UUID id) {
        return documents.findByOrganizationRmaOrderByUploadedAtDesc(rma).stream()
                .filter(m -> m.getId().equals(id)).findFirst().orElseThrow();
    }

    @GetMapping("/{docId}")
    public ResponseEntity<byte[]> download(@PathVariable String rma, @PathVariable UUID docId) {
        requireVisible(rma);
        var doc = documents.findById(docId)
                .filter(d -> d.getOrganizationRma().equals(rma))
                .orElseThrow(() -> new NotFoundException("Документ не найден"));
        ContentDisposition cd = ContentDisposition.attachment()
                .filename(doc.getFileName(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .contentType(MediaType.parseMediaType(doc.getContentType()))
                .body(doc.getData());
    }

    @DeleteMapping("/{docId}")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','COMPANY_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable String rma, @PathVariable UUID docId) {
        requireOwn(rma);
        var doc = documents.findById(docId)
                .filter(d -> d.getOrganizationRma().equals(rma))
                .orElseThrow(() -> new NotFoundException("Документ не найден"));
        documents.delete(doc);
        audit.record(AuditService.DELETE, "ORGANIZATION_DOCUMENT", rma, doc.getFileName(), null);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------

    private void requireOrganizationExists(String rma) {
        if (organizations.findByRma(rma).isEmpty()) {
            throw new NotFoundException("Организация не найдена");
        }
    }

    /** Тенант видит документы только своей организации; платформенные роли — любых. */
    private void requireVisible(String rma) {
        if (currentUser.isTenantScoped()
                && !currentUser.organizationRma().map(rma::equals).orElse(false)) {
            throw new NotFoundException("Организация не найдена");
        }
    }

    /** Запись — только в свою организацию (для тенанта). */
    private void requireOwn(String rma) {
        if (currentUser.isTenantScoped()
                && !currentUser.organizationRma().map(rma::equals).orElse(false)) {
            throw new AccessDeniedException("Доступ только к своей организации");
        }
    }

    private static ResponseStatusException unprocessable(String message) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }

    private static String safeName(String name) {
        if (name == null || name.isBlank()) {
            return "document";
        }
        String trimmed = name.replace("\\", "/");
        trimmed = trimmed.substring(trimmed.lastIndexOf('/') + 1);
        return trimmed.length() > 255 ? trimmed.substring(trimmed.length() - 255) : trimmed;
    }
}
