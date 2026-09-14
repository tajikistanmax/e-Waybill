package tj.mintrans.epd.waybill.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tj.mintrans.epd.waybill.domain.WaybillAttachment;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface WaybillAttachmentRepository extends JpaRepository<WaybillAttachment, UUID> {

    List<Meta> findByWaybillIdOrderByUploadedAtDesc(UUID waybillId);

    long countByWaybillId(UUID waybillId);

    /** Проекция без BLOB — список вложений без содержимого файлов. */
    interface Meta {
        UUID getId();
        UUID getWaybillId();
        String getDocType();
        String getTitle();
        String getFileName();
        String getContentType();
        long getSizeBytes();
        String getUploadedBy();
        OffsetDateTime getUploadedAt();
    }
}
