package tj.mintrans.epd.waybill.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Акт дорожной проверки путевого листа: кто, когда, где проверил и с каким результатом.
 * Запись неизменяема — исправление оформляется новой проверкой (как и титулы Т1–Т6).
 */
@Entity
@Table(name = "waybill_inspection")
public class WaybillInspection {

    /** Проверено, нарушений не выявлено. */
    public static final String PASSED = "PASSED";
    /** Выявлено нарушение, путевой лист заблокирован. */
    public static final String BLOCKED = "BLOCKED";

    @Id
    private UUID id;

    @Column(name = "waybill_id", nullable = false)
    private UUID waybillId;

    @Column(nullable = false)
    private String action = PASSED;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code")
    private InspectionReason reasonCode;

    private String description;
    private String place;
    private BigDecimal lat;
    private BigDecimal lon;

    @Column(name = "protocol_number")
    private String protocolNumber;

    @Column(name = "inspector_rma", nullable = false)
    private String inspectorRma;

    @Column(name = "inspector_name")
    private String inspectorName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public UUID getWaybillId() { return waybillId; }
    public void setWaybillId(UUID waybillId) { this.waybillId = waybillId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public InspectionReason getReasonCode() { return reasonCode; }
    public void setReasonCode(InspectionReason reasonCode) { this.reasonCode = reasonCode; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getPlace() { return place; }
    public void setPlace(String place) { this.place = place; }
    public BigDecimal getLat() { return lat; }
    public void setLat(BigDecimal lat) { this.lat = lat; }
    public BigDecimal getLon() { return lon; }
    public void setLon(BigDecimal lon) { this.lon = lon; }
    public String getProtocolNumber() { return protocolNumber; }
    public void setProtocolNumber(String protocolNumber) { this.protocolNumber = protocolNumber; }
    public String getInspectorRma() { return inspectorRma; }
    public void setInspectorRma(String inspectorRma) { this.inspectorRma = inspectorRma; }
    public String getInspectorName() { return inspectorName; }
    public void setInspectorName(String inspectorName) { this.inspectorName = inspectorName; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
