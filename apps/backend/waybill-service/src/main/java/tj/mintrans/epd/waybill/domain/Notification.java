package tj.mintrans.epd.waybill.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Уведомление организации-перевозчику о событии путевого листа.
 * read_at = null — непрочитано.
 */
@Entity
@Table(name = "notification")
public class Notification {

    @Id
    private UUID id;

    @Column(name = "recipient_rma", nullable = false)
    private String recipientRma;

    @Column(name = "waybill_id")
    private UUID waybillId;

    @Column(nullable = false)
    private String kind;

    @Column(nullable = false)
    private String title;

    @Column
    private String body;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "read_at")
    private OffsetDateTime readAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public String getRecipientRma() { return recipientRma; }
    public void setRecipientRma(String recipientRma) { this.recipientRma = recipientRma; }
    public UUID getWaybillId() { return waybillId; }
    public void setWaybillId(UUID waybillId) { this.waybillId = waybillId; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getReadAt() { return readAt; }
    public void setReadAt(OffsetDateTime readAt) { this.readAt = readAt; }
}
