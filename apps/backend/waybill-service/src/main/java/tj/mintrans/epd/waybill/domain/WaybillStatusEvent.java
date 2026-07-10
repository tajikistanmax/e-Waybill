package tj.mintrans.epd.waybill.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Журнал переходов статусов (event sourcing, append-only). */
@Entity
@Table(name = "waybill_status_event")
public class WaybillStatusEvent {

    @Id
    private UUID id;

    @Column(name = "waybill_id", nullable = false)
    private UUID waybillId;

    @Column(name = "from_status")
    private String fromStatus;

    @Column(name = "to_status", nullable = false)
    private String toStatus;

    private String actor;
    private String reason;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        createdAt = OffsetDateTime.now();
    }

    public static WaybillStatusEvent of(UUID waybillId, WaybillStatus from, WaybillStatus to, String actor, String reason) {
        var e = new WaybillStatusEvent();
        e.waybillId = waybillId;
        e.fromStatus = from != null ? from.name() : null;
        e.toStatus = to.name();
        e.actor = actor;
        e.reason = reason;
        return e;
    }

    public UUID getId() { return id; }
    public UUID getWaybillId() { return waybillId; }
    public String getFromStatus() { return fromStatus; }
    public String getToStatus() { return toStatus; }
    public String getActor() { return actor; }
    public String getReason() { return reason; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
