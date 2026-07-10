package tj.mintrans.epd.waybill.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "waybill")
public class Waybill {

    @Id
    private UUID id;

    @Column(unique = true)
    private String number;

    @Enumerated(EnumType.STRING)
    @Column(name = "waybill_type", nullable = false)
    private WaybillType waybillType;

    @Column(name = "communication_type", nullable = false)
    private String communicationType = "URBAN";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WaybillStatus status = WaybillStatus.DRAFT;

    @Column(name = "med_passed", nullable = false)
    private boolean medPassed;

    @Column(name = "tech_passed", nullable = false)
    private boolean techPassed;

    @Column(name = "valid_from")
    private OffsetDateTime validFrom;

    @Column(name = "valid_to")
    private OffsetDateTime validTo;

    @Column(name = "organization_rma", nullable = false)
    private String organizationRma;

    @Column(name = "vehicle_reg_number", nullable = false)
    private String vehicleRegNumber;

    @Column(name = "driver_rma", nullable = false)
    private String driverRma;

    @Column(name = "second_driver_rma")
    private String secondDriverRma;

    @Column(name = "dispatcher_rma")
    private String dispatcherRma;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "organization_snapshot")
    private Map<String, Object> organizationSnapshot;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "vehicle_snapshot")
    private Map<String, Object> vehicleSnapshot;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "driver_snapshot")
    private Map<String, Object> driverSnapshot;

    /** Вариативные поля конкретного типа ПЛ (shipmentKind, trailers, виза и т.п.). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "type_data")
    private Map<String, Object> typeData;

    private String route;
    private String schedule;

    @Column(name = "odometer_exit")
    private Integer odometerExit;

    @Column(name = "odometer_entry")
    private Integer odometerEntry;

    @Column(name = "special_mark")
    private String specialMark;

    @Column(name = "cancel_reason")
    private String cancelReason;

    @Column(name = "replaces_id")
    private UUID replacesId;

    @Column(name = "replaced_by_id")
    private UUID replacedById;

    @Column(nullable = false)
    private String source = "PORTAL";

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
    public String getNumber() { return number; }
    public void setNumber(String number) { this.number = number; }
    public WaybillType getWaybillType() { return waybillType; }
    public void setWaybillType(WaybillType waybillType) { this.waybillType = waybillType; }
    public String getCommunicationType() { return communicationType; }
    public void setCommunicationType(String communicationType) { this.communicationType = communicationType; }
    public WaybillStatus getStatus() { return status; }
    public void setStatus(WaybillStatus status) { this.status = status; }
    public boolean isMedPassed() { return medPassed; }
    public void setMedPassed(boolean medPassed) { this.medPassed = medPassed; }
    public boolean isTechPassed() { return techPassed; }
    public void setTechPassed(boolean techPassed) { this.techPassed = techPassed; }
    public OffsetDateTime getValidFrom() { return validFrom; }
    public void setValidFrom(OffsetDateTime validFrom) { this.validFrom = validFrom; }
    public OffsetDateTime getValidTo() { return validTo; }
    public void setValidTo(OffsetDateTime validTo) { this.validTo = validTo; }
    public String getOrganizationRma() { return organizationRma; }
    public void setOrganizationRma(String organizationRma) { this.organizationRma = organizationRma; }
    public String getVehicleRegNumber() { return vehicleRegNumber; }
    public void setVehicleRegNumber(String vehicleRegNumber) { this.vehicleRegNumber = vehicleRegNumber; }
    public String getDriverRma() { return driverRma; }
    public void setDriverRma(String driverRma) { this.driverRma = driverRma; }
    public String getSecondDriverRma() { return secondDriverRma; }
    public void setSecondDriverRma(String secondDriverRma) { this.secondDriverRma = secondDriverRma; }
    public String getDispatcherRma() { return dispatcherRma; }
    public void setDispatcherRma(String dispatcherRma) { this.dispatcherRma = dispatcherRma; }
    public Map<String, Object> getOrganizationSnapshot() { return organizationSnapshot; }
    public void setOrganizationSnapshot(Map<String, Object> organizationSnapshot) { this.organizationSnapshot = organizationSnapshot; }
    public Map<String, Object> getVehicleSnapshot() { return vehicleSnapshot; }
    public void setVehicleSnapshot(Map<String, Object> vehicleSnapshot) { this.vehicleSnapshot = vehicleSnapshot; }
    public Map<String, Object> getDriverSnapshot() { return driverSnapshot; }
    public void setDriverSnapshot(Map<String, Object> driverSnapshot) { this.driverSnapshot = driverSnapshot; }
    public Map<String, Object> getTypeData() { return typeData; }
    public void setTypeData(Map<String, Object> typeData) { this.typeData = typeData; }
    public String getRoute() { return route; }
    public void setRoute(String route) { this.route = route; }
    public String getSchedule() { return schedule; }
    public void setSchedule(String schedule) { this.schedule = schedule; }
    public Integer getOdometerExit() { return odometerExit; }
    public void setOdometerExit(Integer odometerExit) { this.odometerExit = odometerExit; }
    public Integer getOdometerEntry() { return odometerEntry; }
    public void setOdometerEntry(Integer odometerEntry) { this.odometerEntry = odometerEntry; }
    public String getSpecialMark() { return specialMark; }
    public void setSpecialMark(String specialMark) { this.specialMark = specialMark; }
    public String getCancelReason() { return cancelReason; }
    public void setCancelReason(String cancelReason) { this.cancelReason = cancelReason; }
    public UUID getReplacesId() { return replacesId; }
    public void setReplacesId(UUID replacesId) { this.replacesId = replacesId; }
    public UUID getReplacedById() { return replacedById; }
    public void setReplacedById(UUID replacedById) { this.replacedById = replacedById; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
