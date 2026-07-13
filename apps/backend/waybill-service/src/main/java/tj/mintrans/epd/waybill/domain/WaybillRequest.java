package tj.mintrans.epd.waybill.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Заявка на путевой лист (driver-initiated): водитель подаёт запрос из своего кабинета,
 * диспетчер его компании проверяет/исправляет и одобряет → создаётся путевой лист (Т1).
 * Статусы: PENDING → APPROVED (создан ПЛ, waybillId) | REJECTED (причина) | CANCELLED (водителем).
 */
@Entity
@Table(name = "waybill_request")
public class WaybillRequest {

    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final String CANCELLED = "CANCELLED";

    @Id
    private UUID id;

    @Column(name = "organization_rma", nullable = false)
    private String organizationRma;

    @Column(name = "driver_rma", nullable = false)
    private String driverRma;

    @Column(name = "driver_name")
    private String driverName;

    @Column(name = "vehicle_reg_number", nullable = false)
    private String vehicleRegNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "waybill_type", nullable = false)
    private WaybillType waybillType;

    @Column(name = "requested_from")
    private LocalDate requestedFrom;

    private Integer odometer;

    @Column(name = "communication_type")
    private String communicationType;

    private String route;
    private String schedule;
    private String notes;

    @Column(nullable = false)
    private String status = PENDING;

    @Column(name = "reject_reason")
    private String rejectReason;

    @Column(name = "waybill_id")
    private UUID waybillId;

    @Column(name = "reviewed_by")
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private OffsetDateTime reviewedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        createdAt = OffsetDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public String getOrganizationRma() { return organizationRma; }
    public void setOrganizationRma(String organizationRma) { this.organizationRma = organizationRma; }
    public String getDriverRma() { return driverRma; }
    public void setDriverRma(String driverRma) { this.driverRma = driverRma; }
    public String getDriverName() { return driverName; }
    public void setDriverName(String driverName) { this.driverName = driverName; }
    public String getVehicleRegNumber() { return vehicleRegNumber; }
    public void setVehicleRegNumber(String vehicleRegNumber) { this.vehicleRegNumber = vehicleRegNumber; }
    public WaybillType getWaybillType() { return waybillType; }
    public void setWaybillType(WaybillType waybillType) { this.waybillType = waybillType; }
    public LocalDate getRequestedFrom() { return requestedFrom; }
    public void setRequestedFrom(LocalDate requestedFrom) { this.requestedFrom = requestedFrom; }
    public Integer getOdometer() { return odometer; }
    public void setOdometer(Integer odometer) { this.odometer = odometer; }
    public String getCommunicationType() { return communicationType; }
    public void setCommunicationType(String communicationType) { this.communicationType = communicationType; }
    public String getRoute() { return route; }
    public void setRoute(String route) { this.route = route; }
    public String getSchedule() { return schedule; }
    public void setSchedule(String schedule) { this.schedule = schedule; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRejectReason() { return rejectReason; }
    public void setRejectReason(String rejectReason) { this.rejectReason = rejectReason; }
    public UUID getWaybillId() { return waybillId; }
    public void setWaybillId(UUID waybillId) { this.waybillId = waybillId; }
    public String getReviewedBy() { return reviewedBy; }
    public void setReviewedBy(String reviewedBy) { this.reviewedBy = reviewedBy; }
    public OffsetDateTime getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(OffsetDateTime reviewedAt) { this.reviewedAt = reviewedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
