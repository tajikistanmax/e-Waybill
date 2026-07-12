package tj.mintrans.epd.masterdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "vehicle")
public class Vehicle {

    @Id
    private UUID id;

    @Column(name = "registration_number", nullable = false, unique = true)
    private String registrationNumber;

    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(name = "transport_type", nullable = false)
    private short transportType;

    private String brand;

    @Column(name = "parking_number")
    private String parkingNumber;

    private Integer capacity;

    private BigDecimal carrying;

    @Column(nullable = false)
    private int odometer;

    private String vincode;

    @Column(name = "year_manufacture")
    private Short yearManufacture;

    @Column(name = "tech_inspection_valid_to")
    private LocalDate techInspectionValidTo;

    @Column(name = "control_card_valid_to")
    private LocalDate controlCardValidTo;

    @Column(name = "insurance_valid_to")
    private LocalDate insuranceValidTo;

    /** ADR (ДОПОГ): свидетельство о допуске ТС к перевозке опасных грузов. */
    @Column(name = "adr_approval_valid_to")
    private LocalDate adrApprovalValidTo;

    @Column(nullable = false)
    private boolean blocked;

    /** MANUAL | UNIFIED (данные объекта — из базы ГАИ через единую платформу). */
    @Column(nullable = false)
    private String source = "MANUAL";

    @Column(name = "synced_at")
    private OffsetDateTime syncedAt;

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
    public String getRegistrationNumber() { return registrationNumber; }
    public void setRegistrationNumber(String registrationNumber) { this.registrationNumber = registrationNumber; }
    public UUID getOrganizationId() { return organizationId; }
    public void setOrganizationId(UUID organizationId) { this.organizationId = organizationId; }
    public short getTransportType() { return transportType; }
    public void setTransportType(short transportType) { this.transportType = transportType; }
    public String getBrand() { return brand; }
    public void setBrand(String brand) { this.brand = brand; }
    public String getParkingNumber() { return parkingNumber; }
    public void setParkingNumber(String parkingNumber) { this.parkingNumber = parkingNumber; }
    public Integer getCapacity() { return capacity; }
    public void setCapacity(Integer capacity) { this.capacity = capacity; }
    public BigDecimal getCarrying() { return carrying; }
    public void setCarrying(BigDecimal carrying) { this.carrying = carrying; }
    public int getOdometer() { return odometer; }
    public void setOdometer(int odometer) { this.odometer = odometer; }
    public String getVincode() { return vincode; }
    public void setVincode(String vincode) { this.vincode = vincode; }
    public Short getYearManufacture() { return yearManufacture; }
    public void setYearManufacture(Short yearManufacture) { this.yearManufacture = yearManufacture; }
    public LocalDate getTechInspectionValidTo() { return techInspectionValidTo; }
    public void setTechInspectionValidTo(LocalDate techInspectionValidTo) { this.techInspectionValidTo = techInspectionValidTo; }
    public LocalDate getControlCardValidTo() { return controlCardValidTo; }
    public void setControlCardValidTo(LocalDate controlCardValidTo) { this.controlCardValidTo = controlCardValidTo; }
    public LocalDate getInsuranceValidTo() { return insuranceValidTo; }
    public void setInsuranceValidTo(LocalDate insuranceValidTo) { this.insuranceValidTo = insuranceValidTo; }
    public LocalDate getAdrApprovalValidTo() { return adrApprovalValidTo; }
    public void setAdrApprovalValidTo(LocalDate adrApprovalValidTo) { this.adrApprovalValidTo = adrApprovalValidTo; }
    public boolean isBlocked() { return blocked; }
    public void setBlocked(boolean blocked) { this.blocked = blocked; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public OffsetDateTime getSyncedAt() { return syncedAt; }
    public void setSyncedAt(OffsetDateTime syncedAt) { this.syncedAt = syncedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
