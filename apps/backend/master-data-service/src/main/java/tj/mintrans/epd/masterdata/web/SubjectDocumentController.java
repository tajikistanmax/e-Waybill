package tj.mintrans.epd.masterdata.web;

import org.springframework.format.annotation.DateTimeFormat;
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
import tj.mintrans.epd.masterdata.domain.Driver;
import tj.mintrans.epd.masterdata.domain.Organization;
import tj.mintrans.epd.masterdata.domain.SubjectDocument;
import tj.mintrans.epd.masterdata.domain.Vehicle;
import tj.mintrans.epd.masterdata.repository.DriverRepository;
import tj.mintrans.epd.masterdata.repository.OrganizationRepository;
import tj.mintrans.epd.masterdata.repository.SubjectDocumentRepository;
import tj.mintrans.epd.masterdata.repository.VehicleRepository;
import tj.mintrans.epd.masterdata.service.AuditService;
import tj.mintrans.epd.masterdata.web.error.NotFoundException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Документы ТС и водителей: прикрепление (PENDING) → одобрение/отклонение.
 *
 * <p>Диспетчер и админ компании прикрепляют документы объектов СВОЕЙ организации;
 * одобряет админ компании или сисадмин. Файлы в БД (bytea), как и документы организации.</p>
 */
@RestController
@RequestMapping("/api/v1/{subject:vehicles|drivers}/{key}/documents")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','COMPANY_ADMIN','DISPATCHER','API_INTEGRATOR')")
public class SubjectDocumentController {

    private static final long MAX_BYTES = 12_000_000;
    private static final Set<String> ALLOWED_TYPES = Set.of(
            "application/pdf", "image/jpeg", "image/png", "image/tiff", "image/heic",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    private static final Set<String> VEHICLE_DOC_TYPES = Set.of(
            "TECH_PASSPORT", "INSURANCE", "TECH_INSPECTION", "LEASE_CONTRACT", "ADR_CERT", "OTHER");
    /**
     * PHOTO / SIGNATURE — фото и подпись водителя (legacy drivers.photo / signature_attach, MIGRATION.md 2.6, 12.11):
     * только изображения; одобренная подпись печатается на бланке ПЛ (waybill-service, «Ронанда (имзо)»).
     */
    private static final Set<String> DRIVER_DOC_TYPES = Set.of(
            "DRIVER_LICENSE", "MED_CERT", "SAFETY_COURSE", "ADR_CERT", "PASSPORT", "PHOTO", "SIGNATURE", "OTHER");
    static final Set<String> VISUAL_DOC_TYPES = Set.of("PHOTO", "SIGNATURE");

    private final SubjectDocumentRepository documents;
    private final VehicleRepository vehicles;
    private final DriverRepository drivers;
    private final OrganizationRepository organizations;
    private final CurrentUser currentUser;
    private final AuditService audit;

    public SubjectDocumentController(SubjectDocumentRepository documents, VehicleRepository vehicles,
                                     DriverRepository drivers, OrganizationRepository organizations,
                                     CurrentUser currentUser, AuditService audit) {
        this.documents = documents;
        this.vehicles = vehicles;
        this.drivers = drivers;
        this.organizations = organizations;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    @GetMapping
    public List<SubjectDocumentRepository.Meta> list(@PathVariable String subject, @PathVariable String key) {
        Subject s = resolve(subject, key);
        return documents.findBySubjectTypeAndSubjectKeyOrderByUploadedAtDesc(s.type(), s.key());
    }

    @PostMapping
    public ResponseEntity<SubjectDocumentRepository.Meta> upload(
            @PathVariable String subject, @PathVariable String key,
            @RequestParam("file") MultipartFile file,
            @RequestParam("docType") String docType,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "validTo", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate validTo) {
        Subject s = resolve(subject, key);
        Set<String> allowed = "VEHICLE".equals(s.type()) ? VEHICLE_DOC_TYPES : DRIVER_DOC_TYPES;
        if (!allowed.contains(docType)) {
            throw unprocessable("Недопустимый вид документа «%s» (для %s: %s)".formatted(docType, subject, allowed));
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
        assertVisualTypeIsImage(docType, contentType);
        if (documents.countBySubjectTypeAndSubjectKey(s.type(), s.key()) >= 50) {
            throw unprocessable("Достигнут предел числа документов объекта (50)");
        }

        var doc = new SubjectDocument();
        doc.setSubjectType(s.type());
        doc.setSubjectKey(s.key());
        doc.setOrganizationRma(s.organizationRma());
        doc.setDocType(docType);
        doc.setTitle(title == null || title.isBlank() ? null : title.trim());
        doc.setValidTo(validTo);
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
        audit.record(AuditService.CREATE, "SUBJECT_DOCUMENT", s.type() + ":" + s.key(), null,
                "%s · %s · %d байт".formatted(docType, doc.getFileName(), file.getSize()));
        return ResponseEntity.status(HttpStatus.CREATED).body(meta(s, doc.getId()));
    }

    /**
     * Последний ОДОБРЕННЫЙ документ вида {@code docType} (файл) — для фото/подписи водителя на бланке ПЛ
     * и в карточке: печать берёт только одобренную подпись (строже legacy, где файл печатался сразу). 404 — нет.
     */
    @GetMapping("/latest")
    public ResponseEntity<byte[]> latest(@PathVariable String subject, @PathVariable String key,
                                         @RequestParam("docType") String docType) {
        Subject s = resolve(subject, key);
        var doc = documents.findFirstBySubjectTypeAndSubjectKeyAndDocTypeAndStatusOrderByUploadedAtDesc(
                        s.type(), s.key(), docType, "APPROVED")
                .orElseThrow(() -> new NotFoundException("Одобренный документ вида " + docType + " не найден"));
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=60")
                .contentType(MediaType.parseMediaType(doc.getContentType()))
                .body(doc.getData());
    }

    @GetMapping("/{docId}")
    public ResponseEntity<byte[]> download(@PathVariable String subject, @PathVariable String key,
                                           @PathVariable UUID docId) {
        Subject s = resolve(subject, key);
        var doc = documents.findById(docId)
                .filter(d -> d.getSubjectType().equals(s.type()) && d.getSubjectKey().equals(s.key()))
                .orElseThrow(() -> new NotFoundException("Документ не найден"));
        ContentDisposition cd = ContentDisposition.attachment()
                .filename(doc.getFileName(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .contentType(MediaType.parseMediaType(doc.getContentType()))
                .body(doc.getData());
    }

    @PostMapping("/{docId}/approve")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','COMPANY_ADMIN')")
    public SubjectDocumentRepository.Meta approve(@PathVariable String subject, @PathVariable String key,
                                                  @PathVariable UUID docId,
                                                  @RequestParam(value = "note", required = false) String note) {
        return review(subject, key, docId, "APPROVED", note);
    }

    @PostMapping("/{docId}/reject")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','COMPANY_ADMIN')")
    public SubjectDocumentRepository.Meta reject(@PathVariable String subject, @PathVariable String key,
                                                 @PathVariable UUID docId,
                                                 @RequestParam(value = "note", required = false) String note) {
        return review(subject, key, docId, "REJECTED", note);
    }

    @DeleteMapping("/{docId}")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','COMPANY_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable String subject, @PathVariable String key,
                                       @PathVariable UUID docId) {
        Subject s = resolve(subject, key);
        var doc = documents.findById(docId)
                .filter(d -> d.getSubjectType().equals(s.type()) && d.getSubjectKey().equals(s.key()))
                .orElseThrow(() -> new NotFoundException("Документ не найден"));
        documents.delete(doc);
        audit.record(AuditService.DELETE, "SUBJECT_DOCUMENT", s.type() + ":" + s.key(), doc.getFileName(), null);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------

    private SubjectDocumentRepository.Meta review(String subject, String key, UUID docId,
                                                  String status, String note) {
        Subject s = resolve(subject, key);
        var doc = documents.findById(docId)
                .filter(d -> d.getSubjectType().equals(s.type()) && d.getSubjectKey().equals(s.key()))
                .orElseThrow(() -> new NotFoundException("Документ не найден"));
        doc.setStatus(status);
        doc.setReviewNote(note == null || note.isBlank() ? null : note.trim());
        doc.setReviewedBy(currentUser.username().orElse(null));
        doc.setReviewedAt(OffsetDateTime.now());
        documents.save(doc);
        audit.record(AuditService.UPDATE, "SUBJECT_DOCUMENT", s.type() + ":" + s.key(),
                doc.getFileName(), status);
        return meta(s, doc.getId());
    }

    private record Subject(String type, String key, String organizationRma) {
    }

    /** Резолвит ТС/водителя, проверяет что объект существует и (для тенанта) свой. */
    private Subject resolve(String subjectPath, String key) {
        if ("vehicles".equals(subjectPath)) {
            Vehicle v = vehicles.findByRegistrationNumber(key.trim().toUpperCase())
                    .orElseThrow(() -> new NotFoundException("Транспорт не найден"));
            requireOwn(v.getOrganizationId());
            return new Subject("VEHICLE", v.getRegistrationNumber(), orgRma(v.getOrganizationId()));
        }
        Driver d = drivers.findByRma(key.trim())
                .orElseThrow(() -> new NotFoundException("Водитель не найден"));
        requireOwn(d.getOrganizationId());
        return new Subject("DRIVER", d.getRma(), orgRma(d.getOrganizationId()));
    }

    private void requireOwn(UUID entityOrgId) {
        if (currentUser.isTenantScoped()) {
            var own = currentUser.organizationRma().flatMap(organizations::findByRma).orElse(null);
            if (own == null || !own.getId().equals(entityOrgId)) {
                throw new AccessDeniedException("Доступ только к объектам своей организации");
            }
        }
    }

    private String orgRma(UUID orgId) {
        return orgId == null ? null
                : organizations.findById(orgId).map(Organization::getRma).orElse(null);
    }

    private SubjectDocumentRepository.Meta meta(Subject s, UUID id) {
        return documents.findBySubjectTypeAndSubjectKeyOrderByUploadedAtDesc(s.type(), s.key()).stream()
                .filter(m -> m.getId().equals(id)).findFirst().orElseThrow();
    }

    private static ResponseStatusException unprocessable(String message) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }

    /** Фото и подпись водителя — только изображения (legacy: image|mimes:jpg,png,jpeg), иначе 422. */
    static void assertVisualTypeIsImage(String docType, String contentType) {
        if (VISUAL_DOC_TYPES.contains(docType) && (contentType == null || !contentType.startsWith("image/"))) {
            throw unprocessable("Для вида «%s» допускается только изображение (JPEG/PNG), получено: %s"
                    .formatted(docType, contentType));
        }
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
