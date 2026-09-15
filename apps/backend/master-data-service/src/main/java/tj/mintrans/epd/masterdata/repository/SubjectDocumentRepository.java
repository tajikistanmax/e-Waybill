package tj.mintrans.epd.masterdata.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tj.mintrans.epd.masterdata.domain.SubjectDocument;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface SubjectDocumentRepository extends JpaRepository<SubjectDocument, UUID> {

    List<Meta> findBySubjectTypeAndSubjectKeyOrderByUploadedAtDesc(String subjectType, String subjectKey);

    long countBySubjectTypeAndSubjectKey(String subjectType, String subjectKey);

    /** Проекция без BLOB — список карточек без содержимого файлов. */
    interface Meta {
        UUID getId();
        String getSubjectType();
        String getSubjectKey();
        String getDocType();
        String getTitle();
        LocalDate getValidTo();
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
