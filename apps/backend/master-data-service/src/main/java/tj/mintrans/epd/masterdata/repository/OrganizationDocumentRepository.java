package tj.mintrans.epd.masterdata.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tj.mintrans.epd.masterdata.domain.OrganizationDocument;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface OrganizationDocumentRepository extends JpaRepository<OrganizationDocument, UUID> {

    /** Метаданные документов организации (без содержимого файла). */
    List<Meta> findByOrganizationRmaOrderByUploadedAtDesc(String organizationRma);

    long countByOrganizationRma(String organizationRma);

    /** Последний документ вида и статуса — печать организации (SEAL, APPROVED) на бланке ПЛ. */
    java.util.Optional<OrganizationDocument> findFirstByOrganizationRmaAndDocTypeAndStatusOrderByUploadedAtDesc(
            String organizationRma, String docType, String status);

    /** Проекция без поля data — список не тянет BLOB'ы в память. */
    interface Meta {
        UUID getId();
        String getOrganizationRma();
        String getDocType();
        String getTitle();
        String getFileName();
        String getContentType();
        long getSizeBytes();
        String getStatus();
        String getReviewNote();
        String getUploadedBy();
        OffsetDateTime getUploadedAt();
        String getReviewedBy();
        OffsetDateTime getReviewedAt();
    }
}
